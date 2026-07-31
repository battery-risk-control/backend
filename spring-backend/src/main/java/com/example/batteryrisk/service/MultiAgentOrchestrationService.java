package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;

@Service
public class MultiAgentOrchestrationService {
    /** severity_engine이 공급망 무관 기사에 붙이는 reason code. */
    private static final String NOT_RELEVANT_REASON_CODE = "NOT_RELEVANT";
    private static final String ANALYSIS_STATUS_COMPLETED = "COMPLETED";

    /**
     * 저장에 남길 가중치 조합 버전. 실제 가중치는 FastAPI
     * {@code app/multi_agent/nodes/risk_node.py}의 상수(0.35/0.45/0.20)이고 응답으로 오지 않으므로
     * Spring이 "지금 적용된다고 믿는 조합"을 기록한다.
     *
     * <p><b>risk_node.py의 가중치를 바꾸면 여기도 반드시 올릴 것.</b> 안 올리면 서로 다른 가중치로
     * 계산된 점수가 같은 버전으로 섞여 과거 점수를 해석할 수 없게 된다.
     */
    private static final String WEIGHT_VERSION = "procurement-risk-v1";

    private final ErpService erpService;
    private final MultiAgentService multiAgentService;
    private final ErpRepository erpRepository;
    private final AnalysisRepository analysisRepository;
    private final ProcurementRiskRepository procurementRiskRepository;

    public MultiAgentOrchestrationService(
        ErpService erpService,
        MultiAgentService multiAgentService,
        ErpRepository erpRepository,
        AnalysisRepository analysisRepository,
        ProcurementRiskRepository procurementRiskRepository
) {
    this.erpService = erpService;
    this.multiAgentService = multiAgentService;
    this.erpRepository = erpRepository;
    this.analysisRepository = analysisRepository;
    this.procurementRiskRepository = procurementRiskRepository;
}

    /**
     * 확정된 외부신호와, 그 출처인 분석에서 함께 끌어온 값들.
     *
     * <p>{@code level}은 항상 대문자로 정규화해 담는다 — FastAPI는 소문자, ERP Exposure Agent는
     * 대문자를 요구해서 원본 표기를 그대로 흘리면 두 경로 중 한쪽이 조용히 어긋난다.
     *
     * <p>{@code analysisId}·{@code materialCategory}·{@code mock}은 요청 본문으로 외부신호를 직접
     * 넣은 경우 알 수 없으므로 각각 null·null·false다.
     */
    private record ExternalSignal(
            int score, String level, UUID analysisId, String materialCategory, boolean mock) {}

    public MultiAgentDto.Response generate(
            MultiAgentDto.GenerateRequest request
    ) {
        ExternalSignal externalSignal =
                resolveExternalSignal(request);

        ErpDto.ContextResponse erp =
                erpService.buildContext(
                        new ErpDto.ContextRequest(
                                request.erpMaterialId(),
                                request.erpSupplierId(),
                                request.asOf()
                        )
                );

        String impactDomain =
                normalizeImpactDomain(
                        request.impactDomainFinal()
                );

        boolean erpAnalysisRequired =
                !impactDomain.equals("OTHER_IRRELEVANT");

        Map<String, Object> erpContext =
                buildErpContext(
                        request,
                        erp,
                        impactDomain,
                        erpAnalysisRequired,
                        externalSignal
                );

        MultiAgentDto.Request fastApiRequest =
                new MultiAgentDto.Request(
                        request.newsId(),
                        request.title(),
                        request.articleText(),
                        request.summaryKr(),
                        request.impactDomainDraft(),
                        request.impactDomainFinal(),
                        List.of(erp.materialName()),
                        request.country(),
                        externalSignal.level()
                                .toLowerCase(Locale.ROOT),
                        externalSignal.score(),
                        erpContext,
                        erp.primaryContractId(),
                        erp.primarySupplierId(),
                        erp.materialId(),
                        request.useLlm()
                );

        MultiAgentDto.Response response =
                multiAgentService.generate(fastApiRequest);

        return persist(
                request, erp, impactDomain, externalSignal, response);
    }

    /** 저장된 구매 리스크 점수를 다시 꺼낸다. 재조회·감사추적용. */
    public ProcurementRiskDto.Assessment getAssessment(UUID assessmentId) {
        return procurementRiskRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PROCUREMENT_RISK_ASSESSMENT_NOT_FOUND));
    }

    /**
     * 결과를 {@code procurement_risk_assessments}에 남기고 assessmentId를 붙여 돌려준다.
     *
     * <p>저장 실패를 삼키지 않는다. 브리핑 한 건은 LangGraph 노드와 LLM 호출을 태운 비싼
     * 결과지만, 조용히 저장을 건너뛰면 "응답으로만 나가고 사라진다"는 원래 문제가 그대로 재현된다.
     * {@code SeverityService.assess()}도 같은 방침이다.
     */
    private MultiAgentDto.Response persist(
            MultiAgentDto.GenerateRequest request,
            ErpDto.ContextResponse erp,
            String impactDomain,
            ExternalSignal externalSignal,
            MultiAgentDto.Response response
    ) {
        UUID assessmentId = UUID.randomUUID();
        procurementRiskRepository.save(new ProcurementRiskDto.Assessment(
                assessmentId,
                externalSignal.analysisId(),
                request.newsId(),
                erp.materialId(),
                erp.erpMaterialId(),
                erp.erpSupplierId(),
                externalSignal.materialCategory(),
                impactDomain,
                request.asOf(),
                BigDecimal.valueOf(externalSignal.score()),
                externalSignal.level(),
                numeric(response.erpAssessment(), "erp_exposure_score"),
                numeric(response.contractAssessment(), "contract_gap_score"),
                BigDecimal.valueOf(response.procurementRiskScore()),
                response.procurementRiskLevel().toUpperCase(Locale.ROOT),
                response.riskReasons() == null ? List.of() : response.riskReasons(),
                response.erpAssessment(),
                response.contractAssessment(),
                WEIGHT_VERSION,
                flag(response.erpAssessment(), "stockout_before_eta"),
                response.reviewPassed(),
                response.llmUsed(),
                externalSignal.mock(),
                OffsetDateTime.now()));
        return response.withAssessmentId(assessmentId);
    }

    /**
     * 세부 점수는 응답 Map 안에 snake_case 키로 들어 있다({@code erp_exposure_score},
     * {@code contract_gap_score}). FastAPI 모델의 alias_generator는 <b>필드 이름만</b> camelCase로
     * 바꾸고 dict 내용은 건드리지 않으므로, 여기서도 snake_case로 꺼낸다.
     */
    private static BigDecimal numeric(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        return source.get(key) instanceof Number number
                ? BigDecimal.valueOf(number.doubleValue())
                : null;
    }

    private static boolean flag(Map<String, Object> source, String key) {
        return source != null && Boolean.TRUE.equals(source.get(key));
    }

    /**
     * 외부신호(가중치 0.35)를 확정한다.
     *
     * <p>{@code analysisId}가 있으면 {@code analyses}에서 읽는다 — F3 {@code /analyze}가 저장한
     * {@code severity_score}가 곧 외부신호 점수다(severity_engine v0.2-realtime,
     * {@code 70×goldstein + 30×article_signal}). 없으면 요청 본문 값을 쓴다.
     *
     * <p>둘 다 비면 예외를 던진다. 이전에는 {@code int} 기본값 0이 그대로 흘러가 FastAPI
     * {@code risk_node}에서 가중치 0.35 항목이 0점 처리됐고, 종합 점수가 최대 35점 낮게 나오는데도
     * 응답은 200이라 아무 신호가 없었다.
     */
    private ExternalSignal resolveExternalSignal(
            MultiAgentDto.GenerateRequest request
    ) {
        if (request.analysisId() == null) {
            return fromRequestBody(request);
        }
        Analysis analysis = analysisRepository
                .findById(request.analysisId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ANALYSIS_NOT_FOUND));

        // 공급망 무관 기사는 여기서 끊는다. 지금도 impactDomain이 "기타/무관"이면 ERP 조회는
        // 건너뛰지만 LangGraph 노드와 LLM 호출은 그대로 돌아 비용만 나간다.
        if (isNotSupplyChainRelevant(analysis)) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_NOT_SUPPLY_CHAIN_RELEVANT);
        }
        if (!ANALYSIS_STATUS_COMPLETED.equals(analysis.getStatus())
                || analysis.getSeverityScore() == null
                || analysis.getSeverity() == null) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_SCORED);
        }
        return new ExternalSignal(
                clampScore(analysis.getSeverityScore()),
                analysis.getSeverity().toUpperCase(Locale.ROOT),
                analysis.getAnalysisId(),
                analysis.getMaterialCategory(),
                analysis.isMock()
        );
    }

    private static ExternalSignal fromRequestBody(
            MultiAgentDto.GenerateRequest request
    ) {
        if (request.externalSignalScore() == null
                || request.externalSignalLevel() == null
                || request.externalSignalLevel().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SIGNAL_REQUIRED);
        }
        return new ExternalSignal(
                request.externalSignalScore(),
                request.externalSignalLevel().toUpperCase(Locale.ROOT),
                null, null, false
        );
    }

    /**
     * {@code analyses.reason_codes}는 {@code String.join(",")}로 저장한 CSV라 split 후 비교한다.
     * severity_engine이 reason code에 쉼표를 넣지 않는 것도 이 파싱 때문이다.
     */
    private static boolean isNotSupplyChainRelevant(Analysis analysis) {
        String reasonCodes = analysis.getReasonCodes();
        if (reasonCodes == null || reasonCodes.isBlank()) {
            return false;
        }
        return Arrays.stream(reasonCodes.split(","))
                .map(String::trim)
                .anyMatch(NOT_RELEVANT_REASON_CODE::equals);
    }

    /** analyses.severity_score는 소수 1자리 double, FastAPI 계약은 0~100 정수다. */
    private static int clampScore(double severityScore) {
        return (int) Math.max(0, Math.min(100, Math.round(severityScore)));
    }

    private Map<String, Object> buildErpContext(
            MultiAgentDto.GenerateRequest request,
            ErpDto.ContextResponse erp,
            String impactDomain,
            boolean erpAnalysisRequired,
            ExternalSignal externalSignal
    ) {
        Map<String, Object> context =
                new LinkedHashMap<>();

        context.put(
                "requestId",
                "ERP-" + UUID.randomUUID()
        );
        context.put("eventId", request.newsId());
        context.put("asOf", request.asOf());
        context.put("impactDomain", impactDomain);
        context.put(
                "externalSignalScore",
                externalSignal.score()
        );
        context.put(
                "externalSignalLevel",
                externalSignal.level()
        );
        context.put(
                "affectedMaterialId",
                erp.erpMaterialId()
        );
        context.put(
                "affectedSupplierId",
                erp.erpSupplierId()
        );
        context.put(
                "primaryContractId",
                erp.erpContractId()
        );
        context.put(
                "eventSummary",
                resolveEventSummary(request)
        );
        context.put(
                "erpAnalysisRequired",
                erpAnalysisRequired
        );

        if (erpAnalysisRequired) {
            context.put(
                    "materialContext",
                    buildMaterialContext(erp)
            );
        }

        /*
         * 다음 단계에서 실제 발주 상세와
         * 대체 공급사 목록을 PostgreSQL에서 연결한다.
         */
        context.put(
        "purchaseOrders",
        erpRepository.findOpenPurchaseOrders(
                        erp.materialId(),
                        request.asOf().toLocalDate()
                )
                .stream()
                .map(row -> {
                    Map<String, Object> order =
                            new LinkedHashMap<>();

                    order.put(
                            "purchaseOrderItemId",
                            row.erpPurchaseOrderItemId()
                    );
                    order.put(
                            "purchaseOrderId",
                            row.erpPurchaseOrderId()
                    );
                    order.put(
                            "materialId",
                            row.erpMaterialId()
                    );
                    order.put(
                            "supplierId",
                            row.erpSupplierId()
                    );
                    order.put(
                            "contractId",
                            row.erpContractId()
                    );
                    order.put(
                            "remainingQuantity",
                            row.remainingQuantity()
                    );
                    order.put(
                            "orderStatus",
                            row.orderStatus()
                    );
                    order.put(
                            "effectiveArrivalDate",
                            row.effectiveArrivalDate() == null
                            ? null
                            : row.effectiveArrivalDate().toString()
                    );
                    order.put(
                            "eligibleForEta",
                            row.effectiveArrivalDate() != null
                                    && !row.effectiveArrivalDate()
                                    .isBefore(
                                            request.asOf()
                                                    .toLocalDate()
                                    )
                    );

                    return order;
                })
                .toList()
);

context.put(
        "alternativeSuppliers",
        erpRepository
                .findEligibleAlternativeSuppliers(
                        erp.materialId(),
                        erp.primarySupplierId(),
                        request.asOf().toLocalDate(),
                        5
                )
                .stream()
                .map(row -> {
                    Map<String, Object> supplier =
                            new LinkedHashMap<>();

                    supplier.put(
                            "supplierId",
                            row.erpSupplierId()
                    );
                    supplier.put("contractId", null);
                    supplier.put(
                            "supplierStatus",
                            "ACTIVE"
                    );
                    supplier.put(
                            "availableCapacityQuantity",
                            null
                    );
                    supplier.put(
                            "leadTimeDays",
                            row.leadTimeDays()
                    );
                    supplier.put(
                            "qualificationStatus",
                            row.approvedStatus()
                    );

                    return supplier;
                })
                .toList()
);

        return context;
    }

    private static Map<String, Object> buildMaterialContext(
            ErpDto.ContextResponse erp
    ) {
        Map<String, Object> material =
                new LinkedHashMap<>();

        material.put(
                "materialId",
                erp.erpMaterialId()
        );
        material.put(
                "materialName",
                erp.materialName()
        );
        material.put("unit", erp.unit());
        material.put(
                "onHandQuantity",
                erp.onHandQuantity()
        );
        material.put(
                "reservedQuantity",
                erp.reservedQuantity()
        );
        material.put(
                "blockedQuantity",
                erp.blockedQuantity()
        );
        material.put(
                "qualityHoldQuantity",
                erp.qualityHoldQuantity()
        );
        material.put(
                "averageDailyUsage",
                erp.averageDailyUsage()
        );
        material.put(
                "safetyStockQuantity",
                erp.safetyStockQuantity()
        );
        material.put(
                "supplierDependencyRatio",
                erp.supplierDependencyRatio()
        );
        material.put(
                "alternativeSupplierStatus",
                erp.alternativeSupplierStatus()
        );
        material.put(
                "supplierStatus",
                erp.supplierStatus()
        );
        material.put(
                "primarySupplierId",
                erp.erpSupplierId()
        );
        material.put(
                "primaryContractId",
                erp.erpContractId()
        );
        material.put(
                "inventorySnapshotAt",
                erp.asOf()
        );

        return material;
    }

    private static String resolveEventSummary(
            MultiAgentDto.GenerateRequest request
    ) {
        if (request.summaryKr() != null
                && !request.summaryKr().isBlank()) {
            return request.summaryKr();
        }

        return request.title();
    }

    private static String normalizeImpactDomain(
            String value
    ) {
        String normalized = value
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "production" -> "PRODUCTION";
            case "logistics" -> "LOGISTICS";
            case "policy" -> "POLICY";
            case "market" -> "MARKET";
            case "geopolitical", "geopolitics" ->
                    "GEOPOLITICS";
            default -> "OTHER_IRRELEVANT";
        };
    }
}
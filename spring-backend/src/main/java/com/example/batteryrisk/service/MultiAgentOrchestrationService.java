package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrationService.class);

    /**
     * 구매 리스크 자동 점수화 스위치. <b>기본 false</b>이며 {@code @Value}·application.yml·
     * docker-compose 세 곳이 모두 false여야 한다 — 수집 스케줄러(F4)에서 이 세 곳이 어긋나
     * 자동 분석이 의도치 않게 돈 적이 있다. 켜는 걸 깜빡하면 점수가 안 쌓일 뿐이지만 끄는 걸
     * 깜빡하면 LLM 비용이 실제로 나가고 되돌릴 수 없다 — 대가가 비대칭이라 안전한 쪽(off)이 기본이다.
     */
    @Value("${app.risk-scoring.enabled:false}")
    private boolean riskScoringEnabled;

    /** 1회 실행당 처리할 분석 수 상한. 자재가 2개인 대분류(LITHIUM/GRAPHITE)는 분석 1건이 호출 2건이 된다. */
    @Value("${app.risk-scoring.batch-size:10}")
    private int riskScoringBatchSize;

    private final ErpService erpService;
    private final MultiAgentService multiAgentService;
    private final ErpRepository erpRepository;
    private final AnalysisRepository analysisRepository;
    private final ProcurementRiskRepository procurementRiskRepository;
    private final KgResolverService kgResolverService;

    public MultiAgentOrchestrationService(
        ErpService erpService,
        MultiAgentService multiAgentService,
        ErpRepository erpRepository,
        AnalysisRepository analysisRepository,
        ProcurementRiskRepository procurementRiskRepository,
        KgResolverService kgResolverService
) {
    this.erpService = erpService;
    this.multiAgentService = multiAgentService;
    this.erpRepository = erpRepository;
    this.analysisRepository = analysisRepository;
    this.procurementRiskRepository = procurementRiskRepository;
    this.kgResolverService = kgResolverService;
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

        String materialCategoryForOutbound =
                externalSignal.materialCategory() != null
                        ? externalSignal.materialCategory()
                        : erpRepository.findMaterialCategory(erp.materialId())
                                .orElse(null);

        OutboundResolutionResult outboundResolution =
                resolveOutboundContract(
                        request.country(),
                        materialCategoryForOutbound
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
                        outboundResolution.contracts(),
                        outboundResolution.totalMatched(),
                        request.useLlm()
                );

        MultiAgentDto.Response response =
                multiAgentService.generate(fastApiRequest);

        return persist(
                request, erp, impactDomain, externalSignal, response);
    }

    /**
     * 아직 점수가 없는 분석을 찾아 구매 리스크 점수를 채운다.
     *
     * <p>비로그인 대시보드는 아무 때나 열리는데 페이지를 열 때마다 LangGraph(supervisor + 노드들,
     * LLM 다중 호출)를 돌릴 수는 없다 — 느리고, 비싸고, 매번 값이 달라진다. 미리 채워두는 것이
     * 이 스케줄러의 목적이다.
     */
    @Scheduled(
            fixedRateString = "${app.risk-scoring.interval-ms:1800000}",
            initialDelayString = "${app.risk-scoring.initial-delay-ms:120000}")
    public void scoreNewAnalysesOnSchedule() {
        if (!riskScoringEnabled) {
            log.debug("구매 리스크 자동 점수화 비활성(app.risk-scoring.enabled=false) — 건너뜀");
            return;
        }
        scoreNewAnalyses(riskScoringBatchSize);
    }

    /**
     * 점수가 없는 분석 최대 {@code limit}건을 처리하고 저장한 건수를 반환한다. 스케줄러와
     * 수동 트리거(F5 관리 API)가 공유한다.
     *
     * <p>분석 한 건이 자재 여러 개로 펼쳐질 수 있다 — {@code analyses}에는 대분류만 있고
     * LITHIUM·GRAPHITE는 ERP 자재가 2개씩이라, 그 경우 두 자재 모두 점수를 낸다. 화면은
     * 대분류 단위로 접으므로 두 행이 같은 카드로 합쳐진다.
     *
     * <p>한 건이 실패해도 배치 전체를 멈추지 않는다(자재 단위 try/catch) — ERP Context가 없는
     * 자재(재고 스냅샷 부재 등)나 FastAPI 일시 장애로 한 건이 막히는 건 흔한데, 그것 때문에
     * 나머지를 못 돌리면 손해가 크다. 대상 0건일 때도 {@code info}로 남긴다 — {@code debug}면
     * "스케줄러가 죽었나, 대상이 없나"를 구분할 수 없다(실제로 겪은 문제).
     */
    public int scoreNewAnalyses(int limit) {
        List<UUID> analysisIds = procurementRiskRepository.findUnscoredAnalysisIds(limit);
        if (analysisIds.isEmpty()) {
            log.info("구매 리스크 점수화 대상 없음");
            return 0;
        }
        int saved = 0;
        int failed = 0;
        for (Analysis analysis : analysisRepository.findAllById(analysisIds)) {
            for (String erpMaterialId
                    : erpRepository.findActiveErpMaterialIds(analysis.getMaterialCategory())) {
                try {
                    generate(scheduledRequest(analysis, erpMaterialId));
                    saved++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn(
                            "구매 리스크 점수화 실패 analysisId={} erpMaterialId={} — 나머지는 계속 진행합니다",
                            analysis.getAnalysisId(), erpMaterialId, exception);
                }
            }
        }
        log.info("구매 리스크 점수화 완료 — 대상 분석 {}건, 저장 {}건, 실패 {}건",
                analysisIds.size(), saved, failed);
        return saved;
    }

    /**
     * 저장된 분석으로 브리핑 요청을 조립한다.
     *
     * <p>외부신호는 {@code analysisId}로 채워지므로 external_signal_* 는 비운다. 공급사도
     * 비운다 — {@code ErpRepository.findSupply}가 supplier가 null이면 {@code priority_rank}
     * 순으로 주 공급사를 고른다.
     *
     * <p>{@code useLlm}은 false다. 스케줄러는 무인 반복 실행이라 LLM 브리핑 문구까지 매번
     * 생성하면 비용이 통제되지 않는다. 점수 산출에는 LLM이 필요 없다.
     */
    private static MultiAgentDto.GenerateRequest scheduledRequest(
            Analysis analysis, String erpMaterialId
    ) {
        return new MultiAgentDto.GenerateRequest(
                "ANALYSIS-" + analysis.getAnalysisId(),
                analysis.getAnalysisId(),
                analysis.getEventTitle(),
                analysis.getEventContent(),
                analysis.getEventTitle(),
                analysis.getImpactDomain(),
                analysis.getImpactDomain(),
                null, null,
                erpMaterialId,
                null,
                analysis.getCountryCode(),
                OffsetDateTime.now(),
                false);
    }

    /** 저장된 구매 리스크 점수를 다시 꺼낸다. 재조회·감사추적용. */
    public ProcurementRiskDto.Assessment getAssessment(UUID assessmentId) {
        return procurementRiskRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PROCUREMENT_RISK_ASSESSMENT_NOT_FOUND));
    }

    /**
     * 구매 리스크 평가를 "완료 처리"한다. 대상이 존재하지 않으면 404 — FK 제약 위반을
     * 그대로 흘려보내지 않고 {@code getAssessment()}와 동일한 사전 확인으로 막는다.
     */
    public ProcurementRiskDto.AcknowledgeResponse acknowledgeAssessment(UUID assessmentId, Long acknowledgedBy) {
        if (procurementRiskRepository.findById(assessmentId).isEmpty()) {
            throw new BusinessException(ErrorCode.PROCUREMENT_RISK_ASSESSMENT_NOT_FOUND);
        }
        return procurementRiskRepository.acknowledge(assessmentId, acknowledgedBy);
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
                OffsetDateTime.now(),
                response.briefing(),
                response.recommendedActions(),
                response.contractFindings(),
                response.warnings()));
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

    /** 재무 노출도 상위 N건으로 추린 아웃바운드 계약 리스트 + kg_service 원래 전체 매칭 건수. */
    private record OutboundResolutionResult(
            List<MultiAgentDto.OutboundContractRef> contracts, int totalMatched) {
        private static final OutboundResolutionResult EMPTY =
                new OutboundResolutionResult(List.of(), 0);
    }

    /** 상세 검색·브리핑에 실을 아웃바운드 계약 상한. 전부 실으면 비용·가독성이 감당 안 된다. */
    private static final int OUTBOUND_CONTRACT_DETAIL_LIMIT = 5;

    /**
     * KG가 확정한 재고부족 원자재와 연결된 아웃바운드(완성차 고객사) 계약을 내부 PK로 리졸브한다.
     *
     * <p>부가 기능이라 실패(매칭 없음/리졸브 안 됨/kg_service 호출 실패)해도 예외를 던지지 않고
     * 빈 리스트로 폴백한다 — {@link KgResolverService}와 같은 방침. FastAPI는 이 값이 비어있으면
     * 아웃바운드 배상책임 조회 단계를 건너뛴다.
     *
     * <p>kg_service는 매칭된 아웃바운드 계약을 전부(수십 건일 수 있음, 2026-07-31 실측 콩고+코발트
     * 21건) 돌려주는데, 전부 리졸브·검색하면 비용·브리핑 가독성이 감당 안 돼서
     * {@link ErpRepository#findTopOutboundContractsByExposure}로 재무 노출도 상위
     * {@link #OUTBOUND_CONTRACT_DETAIL_LIMIT}건만 골라 쓴다(사용자 확정 정책). 원래 전체
     * 매칭 건수는 {@code totalMatched}로 같이 넘겨서 FastAPI가 "이 외 N건 더" 요약에 쓸 수 있게 한다.
     *
     * <p>materialCategory는 외부신호를 요청 본문으로 직접 넣은 경우(analysisId 미사용) 원래
     * null이었다 — 호출부(generate())에서 이제 erp.materialId() 기반으로 폴백해서 채운다
     * (2026-07-31 실증: 이 폴백 없이는 direct-signal 경로로 아웃바운드 배상책임이 한 번도
     * 안 잡혔다). 그래도 폴백 조회(materials.material_category)가 비어 있으면 여전히 null이고,
     * 그때는 {@link KgResolverService#resolve}가 곧바로 매칭없음으로 처리한다.
     */
    private OutboundResolutionResult resolveOutboundContract(
            String countryCode, String materialCategory
    ) {
        KgResolverService.KgResolveResult kgResult =
                kgResolverService.resolve(countryCode, materialCategory);

        if (!kgResult.shortageConfirmed()
                || kgResult.affectedOutboundContractIds() == null
                || kgResult.affectedOutboundContractIds().isEmpty()) {
            return OutboundResolutionResult.EMPTY;
        }

        List<ErpRepository.RankedOutboundContract> ranked =
                erpRepository.findTopOutboundContractsByExposure(
                        kgResult.affectedOutboundContractIds(),
                        OUTBOUND_CONTRACT_DETAIL_LIMIT);

        List<MultiAgentDto.OutboundContractRef> contracts = ranked.stream()
                .map(row -> new MultiAgentDto.OutboundContractRef(
                        row.outboundContractId(), row.productId(), row.customerId()))
                .toList();

        return new OutboundResolutionResult(
                contracts, kgResult.affectedOutboundContractIds().size());
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
            context.put(
                    "requiredQuantity",
                    erp.safetyStockShortageQuantity()
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
                            row.availableCapacityQuantity()
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
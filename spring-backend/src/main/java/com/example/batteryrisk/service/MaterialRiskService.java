package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureRequest;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureResponse;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.MaterialRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/*
 * (설계 메모) 대체 공급사를 Agent에 넘기는 두 가지 경로 중 "요약 상태" 쪽을 골랐다.
 *
 * Agent의 adaptErpExposureRequest()는 두 경로를 지원한다.
 *   ① alternativeSuppliers 원본 목록을 주면 → Agent가 alternativeSupplierStatus를 다시 판정한다.
 *   ② 목록 없이 materialContext.alternativeSupplierStatus만 주면 → 그 값을 그대로 쓴다.
 *
 * ②를 쓴다. ①의 판정(deriveAlternativeSupplierStatus)이 APPROVED로 인정하려면
 * availableCapacityQuantity > 0 이어야 하는데, 그 컬럼은 우리 ERP 스키마에 존재하지 않는다
 * (erp_rules.yaml의 supplierAssessmentRisk.weights.capacity가 같은 이유로 0이다). 그래서 ①로 보내면
 * 승인된 대체 공급사가 있는 자재까지 전부 CONDITIONAL로 강등되고, forcedWarningRules의
 * conditionalAlternativeSupplier가 걸려 10종 <b>전부가 WARNING 이상</b>이 된다 — 실제로 그렇게 나왔다.
 * 등급 열이 전부 "주의"면 화면이 아무것도 못 걸러낸다.
 *
 * ②의 값(ErpService.buildContext → ErpRepository.findAlternativeSupplierStatus)은
 * supplier_materials.approved_status를 그대로 읽은 ERP 사실이다. 우리가 실제로 아는 것만 말한다.
 *
 * 다시 ①로 바꾸려면 buildExposureRequest의 마지막 인자를 대체 공급사 목록으로 채우면 된다.
 * 단 그때는 상세 응답의 alternative_supplier_status(우리 값)와 alternative_supplier_risk_score
 * (Agent가 덮어쓴 값)가 어긋나므로 표시도 함께 손봐야 한다.
 */

/**
 * 1계층 구매팀 "원자재 위험" 화면 — 자재별 ERP 노출도 목록·상세, 계약 RAG 근거, AI 브리핑.
 *
 * <p><b>원천은 ERP 테이블이다.</b> 뉴스가 한 건도 수집되지 않아도 이 화면은 값을 낸다 —
 * "우리 재고·발주·공급사 구조가 지금 얼마나 위태로운가"만 보기 때문이다. 뉴스가 원천인
 * {@link RiskMonitoringService}와 대비되는 지점이다.
 *
 * <p><b>점수와 등급은 Spring이 계산하지 않는다.</b> 자재마다 멀티에이전트의 ERP Exposure Agent
 * ({@code POST /api/v1/internal/erp/exposure})를 호출해 그 결과를 옮긴다. 같은 규칙
 * ({@code fastapi-ai/app/config/erp_rules.yaml})을 Java로 한 벌 더 두면, 나중에 멀티에이전트 쪽
 * 가중치가 바뀔 때 화면과 브리핑이 같은 자재를 다른 점수로 부르게 된다.
 *
 * <p><b>FastAPI 쪽은 한 줄도 고치지 않는다.</b> 대신 Agent가 요구하는 계약을 Spring에서 맞춘다.
 * 아래 세 가지가 그 조정이고, 각각 안 하면 화면이 통째로 UNKNOWN이 되는 것들이다.
 * <ol>
 *   <li>{@code alternativeSupplierStatus} — {@link ErpService#buildContext}가 계산한 값을 보낸다.
 *       {@link ErpExposureRequestService}는 이 자리를 null로 보내는데, 그러면 Agent가
 *       {@code INCOMPLETE}로 판정해 점수를 아예 만들지 않는다.</li>
 *   <li>{@code eligibleForEta} — 납기가 지난 발주도 포함시킨다. 제외하면 {@code nextEtaDays}가
 *       null이 되어 역시 {@code INCOMPLETE}인데, "납기가 지났다"는 건 위험이 <b>더</b> 크다는
 *       뜻이라 화면이 정반대로 나온다. 포함시키면 Agent가 알아서 {@code DELAYED}로 승격한다.</li>
 *   <li>{@code inventorySnapshotAt} — 보내지 않는다. Agent는 재고 스냅샷이 24시간을 넘으면
 *       {@code STALE}로 보고 점수를 만들지 않는데, 우리 ERP는 고정된 mock CSV 시드라 시간이
 *       지나면 전 자재가 자동으로 STALE이 된다. 신선도를 속이는 대신 스냅샷 시각을 상세 응답의
 *       {@code inventory_snapshot_at}으로 그대로 내보내 화면이 직접 판단하게 한다
 *       (Agent도 "신선도를 확인하지 못했다"는 경고를 {@code warnings}에 남긴다).</li>
 * </ol>
 */
@Service
public class MaterialRiskService {
    private static final Logger log = LoggerFactory.getLogger(MaterialRiskService.class);

    private static final String FASTAPI_EXPOSURE_PATH = "/api/v1/internal/erp/exposure";

    /**
     * Agent 계약상 필수지만 ERP 노출도 점수 계산에는 <b>쓰이지 않는</b> 값들.
     *
     * <p>{@code erp_calculator.py}를 확인했다 — 다섯 세부 점수 어디에도 외부신호나 영향 도메인이
     * 들어가지 않는다. 그래서 뉴스 없이 순수 ERP만으로 이 화면의 점수를 만들 수 있다.
     */
    private static final String NEUTRAL_IMPACT_DOMAIN = "PRODUCTION";
    private static final String NEUTRAL_SIGNAL_LEVEL = "NORMAL";
    private static final double NEUTRAL_SIGNAL_SCORE = 0.0;
    private static final String NEUTRAL_EVENT_SUMMARY = "구매팀 원자재 위험 화면의 ERP 노출도 정기 평가";

    private static final int RAG_TOP_K = 5;

    /** 브리핑 외부신호 후보로 훑을 최근 분석 건수. NOT_RELEVANT를 걸러내야 해서 넉넉히 본다. */
    private static final int ANALYSIS_SCAN_SIZE = 20;

    private static final String NOT_RELEVANT_REASON_CODE = "NOT_RELEVANT";
    private static final String IRRELEVANT_IMPACT_DOMAIN = "IRRELEVANT";

    /** 계약 검토 질문이 하나도 없을 때 쓰는 기본 질의. */
    private static final String DEFAULT_CONTRACT_QUERY =
            "납기 지연 통보 의무, 지연 위약금, 불가항력, 대체 공급사 사용 제한 조항";

    /** 나쁠수록 큰 값. 요약의 "전체 데이터 품질"은 자재별 품질 중 가장 나쁜 것을 쓴다. */
    private static final Map<String, Integer> DATA_QUALITY_RANK =
            Map.of("VALID", 0, "STALE", 1, "INCOMPLETE", 2, "INVALID", 3, "UNKNOWN", 4);

    /** 심각 → 주의 → 정상 → 평가불가 순. 같은 등급 안에서는 점수 내림차순. */
    private static final Comparator<MaterialRiskDto.MaterialItem> DISPLAY_ORDER = Comparator
            .comparingInt((MaterialRiskDto.MaterialItem item) -> switch (nullToUnknown(item.exposureLevel())) {
                case "CRITICAL" -> 0;
                case "WARNING" -> 1;
                case "NORMAL" -> 2;
                default -> 3;
            })
            .thenComparing(
                    MaterialRiskDto.MaterialItem::score,
                    Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MaterialRiskDto.MaterialItem::erpMaterialId);

    private final MaterialRiskRepository repository;
    private final ErpRepository erpRepository;
    private final ErpService erpService;
    private final RagService ragService;
    private final AnalysisRepository analysisRepository;
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;
    private final RestClient fastApiRestClient;

    public MaterialRiskService(
            MaterialRiskRepository repository,
            ErpRepository erpRepository,
            ErpService erpService,
            RagService ragService,
            AnalysisRepository analysisRepository,
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            RestClient fastApiRestClient) {
        this.repository = repository;
        this.erpRepository = erpRepository;
        this.erpService = erpService;
        this.ragService = ragService;
        this.analysisRepository = analysisRepository;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.fastApiRestClient = fastApiRestClient;
    }

    /**
     * 화면 1회 로드분 — 상단 KPI + 자재 목록.
     *
     * <p>자재 하나가 실패해도 목록 전체를 죽이지 않는다. 재고 행이 없거나 Agent 호출이 실패한
     * 자재는 점수 없이 사유만 담아 맨 뒤로 보낸다 — 10종 중 1종의 데이터가 빠졌다고 화면이
     * 통째로 빈 것보다, 9종을 보여주고 1종은 왜 못 봤는지 말하는 편이 쓸모 있다.
     */
    public MaterialRiskDto.Overview overview() {
        OffsetDateTime asOf = OffsetDateTime.now();
        List<MaterialRiskDto.MaterialItem> items = repository.findAssessableMaterials().stream()
                .map(material -> assess(material, asOf))
                .map(MaterialRiskService::toItem)
                .sorted(DISPLAY_ORDER)
                .toList();
        return new MaterialRiskDto.Overview(summarize(items, asOf), items);
    }

    /** 우측 상세 패널. 평가에 실패한 자재도 404가 아니라 사유를 담아 200으로 돌려준다. */
    public MaterialRiskDto.MaterialDetail detail(String erpMaterialId) {
        OffsetDateTime asOf = OffsetDateTime.now();
        Assessment assessment = assess(findMaterial(erpMaterialId), asOf);
        MaterialRiskDto.MaterialItem item = toItem(assessment);

        if (assessment.failed()) {
            return unavailableDetail(item, asOf);
        }

        ErpDto.ContextResponse erp = assessment.erp();
        ExposureResponse exposure = assessment.exposure();
        String blockedReason = briefingBlockedReason(assessment.material().materialCategory());

        return new MaterialRiskDto.MaterialDetail(
                item.erpMaterialId(),
                item.materialName(),
                item.materialCategory(),
                item.grade(),
                item.exposureLevel(),
                item.score(),
                item.inventoryDays(),
                item.safetyStockDays(),
                item.supplierDependencyRatio(),
                item.dataQualityStatus(),
                item.unavailableReason(),
                erp.unit(),
                erp.onHandQuantity(),
                erp.availableQuantity(),
                erp.safetyStockQuantity(),
                erp.averageDailyUsage(),
                erp.nextInboundDate(),
                erp.nextEtaDays(),
                round(erp.expectedSupplyGapDays(), 1),
                repository.findInventorySnapshotAt(erp.materialId(), asOf),
                toPrimarySupplier(erp),
                linkedContract(erp),
                toRiskComponents(exposure),
                exposure.forcedCritical(),
                exposure.contractReviewRequired(),
                toContractQuestions(exposure),
                nullToEmpty(exposure.warnings()),
                blockedReason == null,
                blockedReason,
                asOf);
    }

    /**
     * "계약 RAG 근거 보기" — 지금 이 자재가 처한 상황에 맞는 계약 조항을 찾아온다.
     *
     * <p>질의를 새로 만들지 않고 <b>ERP Agent가 이미 만들어 준 질문</b>
     * ({@code questionsForContractAgent})을 쓴다. 어떤 상황에 어떤 조항을 물어야 하는지는
     * {@code erp_rules.yaml}의 {@code contractQuestionRules}에 이미 정의돼 있고, 그 판단을
     * 화면이 따로 흉내 내면 두 곳이 어긋난다.
     */
    public MaterialRiskDto.ContractEvidence contractEvidence(String erpMaterialId) {
        OffsetDateTime asOf = OffsetDateTime.now();
        Assessment assessment = assess(findMaterial(erpMaterialId), asOf);
        if (assessment.failed()) {
            throw new BusinessException(ErrorCode.ERP_CONTEXT_NOT_FOUND, assessment.failureReason());
        }

        ErpDto.ContextResponse erp = assessment.erp();
        if (erp.primaryContractId() == null) {
            throw new BusinessException(
                    ErrorCode.ERP_CONTRACT_NOT_FOUND,
                    "이 자재의 주 공급사와 연결된 계약이 없어 계약 근거를 찾을 수 없습니다.");
        }

        List<MaterialRiskDto.ContractQuestion> questions = toContractQuestions(assessment.exposure());
        String query = questions.isEmpty()
                ? DEFAULT_CONTRACT_QUERY
                : String.join(" ", questions.stream().map(MaterialRiskDto.ContractQuestion::question).toList());

        RagDto.SearchResult result = ragService.search(new RagDto.SearchRequest(
                query,
                new RagDto.SearchFilters(
                        erp.primaryContractId(), erp.primarySupplierId(), erp.materialId(), null, null),
                RAG_TOP_K));

        return new MaterialRiskDto.ContractEvidence(
                erp.erpMaterialId(), linkedContract(erp), questions, query, result.results(), result.mock());
    }

    /**
     * "AI 브리핑 생성" — 이 자재에 대해 멀티에이전트를 실행한다.
     *
     * <p>멀티에이전트는 외부신호(가중치 0.35)를 필수로 요구하는데 이 화면에는 뉴스가 없다.
     * 그래서 <b>DB에 이미 저장된</b> {@code analyses} 중 같은 자재 대분류의 가장 최신 분석을
     * 끌어와 그 {@code analysisId}를 넘긴다 — 새로 수집하거나 분석을 돌리지 않는다.
     *
     * <p>쓸 분석이 없으면 422로 막는다. 외부신호를 0점으로 밀어 넣으면 종합 점수가 최대 35점
     * 낮게 나오는데 응답은 200이라, 화면에서는 "위험하지 않다"로 읽히기 때문이다.
     *
     * @param useLlm 브리핑 문구 생성에 LLM을 쓸지. 버튼 한 번이 곧 비용이라 기본은 false다.
     */
    public MaterialRiskDto.Briefing briefing(String erpMaterialId, boolean useLlm) {
        MaterialRiskRepository.MaterialRow material = findMaterial(erpMaterialId);
        OffsetDateTime asOf = OffsetDateTime.now();

        ErpDto.ContextResponse erp = erpService.buildContext(
                new ErpDto.ContextRequest(material.erpMaterialId(), null, asOf));

        Analysis analysis = findExternalSignalAnalysis(material.materialCategory())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MATERIAL_BRIEFING_NOT_AVAILABLE,
                        briefingBlockedReason(material.materialCategory())));

        MultiAgentDto.GenerateRequest request = new MultiAgentDto.GenerateRequest(
                analysis.getAnalysisId().toString(),
                analysis.getAnalysisId(),
                analysis.getEventTitle(),
                analysis.getEventContent() == null ? "" : analysis.getEventContent(),
                analysis.getSummaryKr() == null ? "" : analysis.getSummaryKr(),
                analysis.getImpactDomain(),
                analysis.getImpactDomain(),
                null, null,
                erp.erpMaterialId(),
                erp.erpSupplierId(),
                analysis.getCountryCode(),
                asOf,
                useLlm);

        MultiAgentDto.Response response = multiAgentOrchestrationService.generate(request);

        // ERP 노출도 점수의 존재로 조기 종료를 판별한다(RiskMonitoringService.isComposite와 같은 기준).
        // 조기 종료 경로는 erpAssessment를 빈 dict로 두므로 이 값이 반드시 없고, 정상 실행은 반드시 있다.
        boolean completed = numeric(response.erpAssessment(), "erp_exposure_score") != null;
        log.info("원자재 위험 AI 브리핑 생성: material={}, analysisId={}, level={}, completed={}",
                erp.erpMaterialId(), analysis.getAnalysisId(), response.procurementRiskLevel(), completed);

        return new MaterialRiskDto.Briefing(
                response.assessmentId(),
                erp.erpMaterialId(),
                erp.materialName(),
                analysis.getAnalysisId(),
                analysis.getEventTitle(),
                analysis.getCountryCode(),
                analysis.getSeverityScore(),
                completed,
                upperOrNull(response.procurementRiskLevel()),
                response.procurementRiskScore(),
                nullToEmpty(response.riskReasons()),
                nullToEmpty(response.recommendedActions()),
                response.briefing(),
                response.llmUsed(),
                response.llmError(),
                response.reviewPassed(),
                nullToEmpty(response.warnings()));
    }

    // --- ERP Exposure Agent 호출 ---------------------------------------------------------------

    /** 자재 1건 평가. 실패해도 예외를 밖으로 던지지 않고 사유를 담아 돌려준다(목록이 죽지 않게). */
    private Assessment assess(MaterialRiskRepository.MaterialRow material, OffsetDateTime asOf) {
        try {
            ErpDto.ContextResponse erp = erpService.buildContext(
                    new ErpDto.ContextRequest(material.erpMaterialId(), null, asOf));
            return new Assessment(material, erp, callExposureAgent(erp, asOf), null);
        } catch (BusinessException exception) {
            log.info("자재 {} ERP Context 구성 실패: {}", material.erpMaterialId(), exception.getMessage());
            return new Assessment(material, null, null, exception.getMessage());
        } catch (Exception exception) {
            log.warn("자재 {} ERP 노출도 계산 실패", material.erpMaterialId(), exception);
            return new Assessment(material, null, null,
                    "ERP 노출도 분석 서버에 연결할 수 없어 점수를 계산하지 못했습니다.");
        }
    }

    private ExposureResponse callExposureAgent(ErpDto.ContextResponse erp, OffsetDateTime asOf) {
        ExposureResponse response = fastApiRestClient.post()
                .uri(FASTAPI_EXPOSURE_PATH)
                .body(buildExposureRequest(erp, asOf))
                .retrieve()
                .body(ExposureResponse.class);
        if (response == null) {
            throw new IllegalStateException("ERP Exposure Agent 응답이 비어 있습니다.");
        }
        return response;
    }

    private ExposureRequest buildExposureRequest(ErpDto.ContextResponse erp, OffsetDateTime asOf) {
        return new ExposureRequest(
                "MATERIAL-RISK-" + UUID.randomUUID(),
                "MATERIAL-RISK-" + erp.erpMaterialId(),
                asOf.toInstant(),
                NEUTRAL_IMPACT_DOMAIN,
                NEUTRAL_SIGNAL_SCORE,
                NEUTRAL_SIGNAL_LEVEL,
                erp.erpMaterialId(),
                erp.erpSupplierId(),
                null,
                erp.erpContractId(),
                NEUTRAL_EVENT_SUMMARY,
                true,
                buildMaterialContext(erp),
                buildPurchaseOrders(erp, asOf),
                // 대체 공급사는 "요약 상태" 경로로 보낸다 — 바로 아래 주석 참고.
                List.of());
    }

    private static ErpExposureDto.MaterialContext buildMaterialContext(ErpDto.ContextResponse erp) {
        return new ErpExposureDto.MaterialContext(
                erp.erpMaterialId(),
                erp.materialName(),
                erp.unit(),
                erp.onHandQuantity(),
                erp.reservedQuantity(),
                erp.blockedQuantity(),
                erp.qualityHoldQuantity(),
                erp.averageDailyUsage(),
                erp.safetyStockQuantity(),
                erp.supplierDependencyRatio(),
                erp.alternativeSupplierStatus(),
                mapSupplierStatus(erp.supplierStatus()),
                erp.erpSupplierId(),
                erp.erpContractId(),
                // 재고 신선도 검사를 태우지 않는다 — 클래스 javadoc 3번 참고.
                null);
    }

    /**
     * 미입고 발주 목록.
     *
     * <p>{@code eligibleForEta}에 납기 경과 여부를 넣지 않는다(클래스 javadoc 2번). Agent의
     * {@code normalizePurchaseOrderStatus()}가 "도착 예정일이 지났는데 잔여 수량이 남았다"를
     * {@code DELAYED}로 승격시키고, 그게 곧 발주지연 위험점수 100점이 된다.
     */
    private List<ErpExposureDto.PurchaseOrderContext> buildPurchaseOrders(
            ErpDto.ContextResponse erp, OffsetDateTime asOf) {
        return erpRepository.findOpenPurchaseOrders(erp.materialId(), asOf.toLocalDate()).stream()
                .filter(row -> row.effectiveArrivalDate() != null)
                .map(row -> new ErpExposureDto.PurchaseOrderContext(
                        row.erpPurchaseOrderItemId(),
                        row.erpPurchaseOrderId(),
                        row.erpMaterialId(),
                        row.erpSupplierId(),
                        row.erpContractId(),
                        row.remainingQuantity(),
                        row.orderStatus(),
                        row.effectiveArrivalDate().toString(),
                        !"CLOSED".equals(row.orderStatus())))
                .toList();
    }

    /** 우리 supplier_status(ACTIVE/UNDER_REVIEW/INACTIVE)를 Agent 계약(…/SUSPENDED/TERMINATED)에 맞춘다. */
    private static String mapSupplierStatus(String supplierStatus) {
        return "INACTIVE".equals(supplierStatus) ? "SUSPENDED" : supplierStatus;
    }

    // --- 응답 조립 ------------------------------------------------------------------------------

    private static MaterialRiskDto.MaterialItem toItem(Assessment assessment) {
        MaterialRiskRepository.MaterialRow material = assessment.material();
        if (assessment.failed()) {
            return new MaterialRiskDto.MaterialItem(
                    material.erpMaterialId(), material.materialName(), material.materialCategory(),
                    null, "UNKNOWN", null, null, null, null, "UNKNOWN", assessment.failureReason());
        }

        ErpDto.ContextResponse erp = assessment.erp();
        ExposureResponse exposure = assessment.exposure();
        String level = nullToUnknown(exposure.exposureLevel());
        BigDecimal score = exposure.erpExposureScore() == null
                ? null
                : BigDecimal.valueOf(exposure.erpExposureScore());

        return new MaterialRiskDto.MaterialItem(
                erp.erpMaterialId(),
                erp.materialName(),
                material.materialCategory(),
                RiskEventService.gradeOf(level),
                level,
                score,
                round(erp.inventoryDays(), 1),
                round(erp.safetyStockDays(), 1),
                round(erp.supplierDependencyRatio(), 4),
                nullToUnknown(exposure.dataQualityStatus()),
                score == null ? scoreMissingReason(exposure) : null);
    }

    /**
     * 데이터 품질 때문에 점수가 안 나온 경우의 사유.
     *
     * <p>Agent는 필수 값이 하나라도 비면 점수를 <b>만들지 않는다</b>(부분 계산으로 낮은 점수를
     * 내보내면 "위험하지 않다"로 오독되므로). 그때 왜 못 만들었는지는 warnings에 들어 있다.
     */
    private static String scoreMissingReason(ExposureResponse exposure) {
        List<String> warnings = nullToEmpty(exposure.warnings());
        if (!warnings.isEmpty()) {
            return warnings.get(0);
        }
        return "ERP 데이터 품질이 " + nullToUnknown(exposure.dataQualityStatus()) + "이라 점수를 산출하지 못했습니다.";
    }

    private static MaterialRiskDto.Summary summarize(
            List<MaterialRiskDto.MaterialItem> items, OffsetDateTime asOf) {
        long unavailable = items.stream().filter(item -> item.score() == null).count();
        List<MaterialRiskDto.MaterialItem> assessed =
                items.stream().filter(item -> item.score() != null).toList();

        List<BigDecimal> inventoryDays = assessed.stream()
                .map(MaterialRiskDto.MaterialItem::inventoryDays)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal average = inventoryDays.isEmpty()
                ? null
                : inventoryDays.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(inventoryDays.size()), 1, RoundingMode.HALF_UP);

        String dataQuality = assessed.stream()
                .map(MaterialRiskDto.MaterialItem::dataQualityStatus)
                .max(Comparator.comparingInt(MaterialRiskService::dataQualityRank))
                .orElse("UNKNOWN");

        return new MaterialRiskDto.Summary(
                assessed.size(),
                countLevel(items, "CRITICAL"),
                countLevel(items, "WARNING"),
                countLevel(items, "NORMAL"),
                unavailable,
                average,
                dataQuality,
                asOf);
    }

    private static MaterialRiskDto.MaterialDetail unavailableDetail(
            MaterialRiskDto.MaterialItem item, OffsetDateTime asOf) {
        return new MaterialRiskDto.MaterialDetail(
                item.erpMaterialId(), item.materialName(), item.materialCategory(),
                null, "UNKNOWN", null, null, null, null, "UNKNOWN", item.unavailableReason(),
                null, null, null, null, null, null, null, null, null,
                null, null, null, false, false, List.of(), List.of(),
                false, item.unavailableReason(), asOf);
    }

    private MaterialRiskDto.PrimarySupplier toPrimarySupplier(ErpDto.ContextResponse erp) {
        return new MaterialRiskDto.PrimarySupplier(
                erp.erpSupplierId(),
                repository.findSupplierName(erp.primarySupplierId()),
                erp.supplierStatus(),
                erp.alternativeSupplierStatus(),
                erp.feocStatus());
    }

    private MaterialRiskDto.LinkedContract linkedContract(ErpDto.ContextResponse erp) {
        return erp.primaryContractId() == null
                ? null
                : repository.findContract(erp.primaryContractId()).orElse(null);
    }

    private static MaterialRiskDto.RiskComponents toRiskComponents(ExposureResponse exposure) {
        Map<String, Object> components = exposure.riskComponents();
        return new MaterialRiskDto.RiskComponents(
                numeric(components, "gapRiskScore"),
                numeric(components, "safetyStockRiskScore"),
                numeric(components, "dependencyRiskScore"),
                numeric(components, "purchaseOrderDelayRiskScore"),
                numeric(components, "alternativeSupplierRiskScore"));
    }

    private static List<MaterialRiskDto.ContractQuestion> toContractQuestions(ExposureResponse exposure) {
        if (exposure == null || exposure.questionsForContractAgent() == null) {
            return List.of();
        }
        return exposure.questionsForContractAgent().stream()
                .map(question -> new MaterialRiskDto.ContractQuestion(
                        text(question, "questionCode"), text(question, "question")))
                .filter(question -> question.question() != null)
                .toList();
    }

    // --- 브리핑 외부신호 ------------------------------------------------------------------------

    /**
     * 브리핑을 못 만드는 이유. 만들 수 있으면 null.
     *
     * <p>상세 응답의 {@code briefing_available}과 실제 실행이 같은 판정을 쓰도록 한 곳에 둔다.
     */
    private String briefingBlockedReason(String materialCategory) {
        if (materialCategory == null || materialCategory.isBlank()) {
            return "자재 대분류가 지정되지 않아 관련 뉴스를 찾을 수 없습니다.";
        }
        if (findExternalSignalAnalysis(materialCategory).isEmpty()) {
            return "저장된 " + materialCategory + " 관련 뉴스 분석이 없어 외부신호를 만들 수 없습니다. "
                    + "뉴스 수집·분석이 한 번 돌아야 브리핑을 생성할 수 있습니다.";
        }
        return null;
    }

    /**
     * 외부신호로 쓸 분석 1건 — 같은 자재 대분류의 <b>이미 저장된</b> 가장 최신 분석.
     *
     * <p>{@link MultiAgentOrchestrationService}가 거부하는 조건을 여기서 미리 걸러낸다. 안 그러면
     * 버튼이 눌리는데 422가 나가고, 화면은 이유를 모른 채 실패만 본다.
     */
    private Optional<Analysis> findExternalSignalAnalysis(String materialCategory) {
        if (materialCategory == null || materialCategory.isBlank()) {
            return Optional.empty();
        }
        return analysisRepository
                .findScoredByMaterialCategory(materialCategory, PageRequest.of(0, ANALYSIS_SCAN_SIZE))
                .stream()
                .filter(analysis -> !hasNotRelevantReason(analysis))
                .filter(analysis -> analysis.getImpactDomain() != null
                        && !IRRELEVANT_IMPACT_DOMAIN.equalsIgnoreCase(analysis.getImpactDomain()))
                .findFirst();
    }

    /** {@code analyses.reason_codes}는 {@code String.join(",")}로 저장한 CSV라 split 후 비교한다. */
    private static boolean hasNotRelevantReason(Analysis analysis) {
        String raw = analysis.getReasonCodes();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return Arrays.stream(raw.split(",")).map(String::trim).anyMatch(NOT_RELEVANT_REASON_CODE::equals);
    }

    // --- 보조 --------------------------------------------------------------------------------

    private MaterialRiskRepository.MaterialRow findMaterial(String erpMaterialId) {
        String normalized = erpMaterialId == null ? null : erpMaterialId.trim();
        return repository.findAssessableMaterials().stream()
                .filter(material -> material.erpMaterialId().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ERP_MATERIAL_NOT_FOUND));
    }

    private static long countLevel(List<MaterialRiskDto.MaterialItem> items, String level) {
        return items.stream()
                .filter(item -> item.score() != null && level.equals(item.exposureLevel()))
                .count();
    }

    private static int dataQualityRank(String status) {
        return DATA_QUALITY_RANK.getOrDefault(nullToUnknown(status), 4);
    }

    private static BigDecimal numeric(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        return source.get(key) instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : value.toString();
    }

    /** 화면 표기용 반올림. ERP 계산은 소수 14자리까지 가는데 그대로 내보내면 화면이 읽을 수 없다. */
    private static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    /** FastAPI는 등급을 소문자로 돌려준다. 화면·필터가 대소문자를 신경 쓰지 않도록 여기서 통일한다. */
    private static String upperOrNull(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 자재 1건의 평가 결과. {@code failureReason}이 있으면 {@code erp}·{@code exposure}는 null이다.
     */
    private record Assessment(
            MaterialRiskRepository.MaterialRow material,
            ErpDto.ContextResponse erp,
            ExposureResponse exposure,
            String failureReason) {

        boolean failed() {
            return failureReason != null;
        }
    }
}

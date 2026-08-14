package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureRequest;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureResponse;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.MaterialRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1계층 구매팀 "원자재 위험" 화면 — 자재별 ERP 노출도 목록·상세, 계약 RAG 근거.
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
 * <p><b>Agent에 보낼 입력은 {@link ErpExposureContextFactory}가 만든다.</b> 멀티에이전트 브리핑과
 * 똑같은 입력을 보내야 같은 자재가 두 화면에서 같은 점수를 갖는다 — 조립 규칙과 그렇게 정한
 * 근거는 그 클래스의 javadoc에 있다.
 *
 * <p><b>AI 브리핑은 이 서비스가 실행하지 않는다.</b> 화면의 "AI 브리핑 생성" 버튼은
 * {@code /purchasing/ai-briefing} 화면으로 이동시키고, 실행·저장·근거 표시는 전부
 * {@link AiBriefingService}가 맡는다. 여기서는 {@link #detail}이 <b>실행할 수 있는지만</b>
 * 판정해 내려준다({@code briefing_available}) — 못 돌릴 대상으로 이동시키면 빈 화면만 보여주는
 * 셈이기 때문이다.
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

    /**
     * 계약 검토 질문이 하나도 없을 때 쓰는 기본 질의.
     *
     * <p>응답의 {@code questions}에도 이 코드로 넣어 보낸다 — 화면이 "무엇을 검색했는지" 늘 알 수
     * 있어야 어떤 조항이 왜 걸렸는지 읽을 수 있다.
     */
    private static final String DEFAULT_CONTRACT_QUESTION_CODE = "DEFAULT_CONTRACT_REVIEW";
    private static final String DEFAULT_CONTRACT_QUERY =
            "납기 지연 통보 의무, 지연 위약금, 불가항력, 대체 공급사 사용 제한 조항";

    /**
     * 재고 스냅샷을 "오래됐다"고 볼 기준.
     *
     * <p>{@code erp_rules.yaml}의 {@code dataQualityRules.maximumInventoryAgeHours}와 같은 값이다.
     * Agent에는 스냅샷 시각을 보내지 않으므로({@link ErpExposureContextFactory} javadoc 3번)
     * 노후 판정은 Spring이 직접 한다 — 안 하면 고정 mock 시드가 몇 달이 지나도 화면에서 계속
     * "데이터 품질 VALID"로 보인다.
     */
    private static final long INVENTORY_SNAPSHOT_MAX_AGE_HOURS = 24;

    private static final String STALE = "STALE";

    /**
     * 개요 캐시 수명. ERP 스냅샷이 하루 단위로 갱신되는 데이터라 초 단위 신선도는 의미가 없고,
     * 짧게 잡아 "새로고침을 눌렀는데 옛 숫자"가 오래 남지 않게 한다.
     */
    private static final long OVERVIEW_CACHE_TTL_SECONDS = 60;

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

    /**
     * 개요 캐시. 자재 목록이 통째로 한 덩어리라 항목별 캐시가 필요 없고, 참조 교체만 하므로
     * 락 없이 {@link AtomicReference} 하나로 충분하다. 동시에 두 요청이 들어오면 둘 다 계산하고
     * 나중 것이 남는데, 결과가 같으므로 문제되지 않는다(계산이 부수효과가 없다).
     */
    private final AtomicReference<CachedOverview> overviewCache = new AtomicReference<>();

    private final MaterialRiskRepository repository;
    private final ErpService erpService;
    private final ErpExposureContextFactory contextFactory;
    private final RagService ragService;
    private final AnalysisRepository analysisRepository;
    private final RestClient fastApiRestClient;
    private final RiskEventService riskEventService;

    public MaterialRiskService(
            MaterialRiskRepository repository,
            ErpService erpService,
            ErpExposureContextFactory contextFactory,
            RagService ragService,
            AnalysisRepository analysisRepository,
            RestClient fastApiRestClient,
            RiskEventService riskEventService) {
        this.repository = repository;
        this.erpService = erpService;
        this.contextFactory = contextFactory;
        this.ragService = ragService;
        this.analysisRepository = analysisRepository;
        this.fastApiRestClient = fastApiRestClient;
        this.riskEventService = riskEventService;
    }

    /**
     * 화면 1회 로드분 — 상단 KPI + 자재 목록.
     *
     * <p>자재 하나가 실패해도 목록 전체를 죽이지 않는다. 재고 행이 없거나 Agent 호출이 실패한
     * 자재는 점수 없이 사유만 담아 맨 뒤로 보낸다 — 10종 중 1종의 데이터가 빠졌다고 화면이
     * 통째로 빈 것보다, 9종을 보여주고 1종은 왜 못 봤는지 말하는 편이 쓸모 있다.
     *
     * <p><b>{@value #OVERVIEW_CACHE_TTL_SECONDS}초 캐시가 붙어 있다.</b> 한 번 부를 때 자재 수만큼
     * ERP Exposure Agent를 호출하므로(현재 10종 · 실측 0.8초), 여러 사용자가 화면을 열거나 탭을
     * 오갈 때마다 그대로 다시 도는 것을 막는다. 캐시된 응답인지는 {@code as_of}로 알 수 있다 —
     * 계산 시각을 응답에 담아 보내므로 화면이 "언제 기준 숫자인지"를 감추지 않는다.
     *
     * @param refresh 화면의 "새로고침"이 누르는 경로. true면 캐시를 건너뛰고 다시 계산한다 —
     *                새로고침을 눌렀는데 같은 숫자가 나오면 버튼이 고장 난 것으로 보인다.
     */
    public MaterialRiskDto.Overview overview(boolean refresh) {
        CachedOverview cached = overviewCache.get();
        if (!refresh && cached != null && cached.isFresh()) {
            return cached.overview();
        }
        MaterialRiskDto.Overview computed = computeOverview();
        overviewCache.set(new CachedOverview(computed, Instant.now()));
        return computed;
    }

    private MaterialRiskDto.Overview computeOverview() {
        OffsetDateTime asOf = OffsetDateTime.now();
        // 헤더 '기준' 칩은 계산 시각(asOf)이 아니라 최신 공급망 뉴스 수집 시각을 쓴다(2·3계층과 동일 소스).
        OffsetDateTime newsAsOf = riskEventService.latestSupplyChainNewsCollectedAt();
        List<MaterialRiskDto.MaterialItem> items = repository.findAssessableMaterials().stream()
                .map(material -> assess(material, asOf))
                .map(assessment -> toItem(assessment, asOf))
                .sorted(DISPLAY_ORDER)
                .toList();
        return new MaterialRiskDto.Overview(summarize(items, asOf, newsAsOf), items);
    }

    /** 우측 상세 패널. 평가에 실패한 자재도 404가 아니라 사유를 담아 200으로 돌려준다. */
    public MaterialRiskDto.MaterialDetail detail(String erpMaterialId) {
        OffsetDateTime asOf = OffsetDateTime.now();
        Assessment assessment = assess(findMaterial(erpMaterialId), asOf);
        MaterialRiskDto.MaterialItem item = toItem(assessment, asOf);

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
                assessment.inventorySnapshotAt(),
                toPrimarySupplier(erp),
                linkedContract(erp),
                toRiskComponents(exposure),
                exposure.forcedCritical(),
                exposure.contractReviewRequired(),
                toContractQuestions(exposure),
                warnings(exposure, assessment.inventorySnapshotAt(), asOf),
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

        // 질문이 없으면 기본 질의로 검색하되, 그 기본 질의도 questions에 담아 내보낸다.
        // 빈 배열로 두면 화면이 "무엇을 검색했는지" 알 수 없어 결과를 해석할 수 없다.
        List<MaterialRiskDto.ContractQuestion> agentQuestions = toContractQuestions(assessment.exposure());
        List<MaterialRiskDto.ContractQuestion> questions = agentQuestions.isEmpty()
                ? List.of(new MaterialRiskDto.ContractQuestion(
                        DEFAULT_CONTRACT_QUESTION_CODE, DEFAULT_CONTRACT_QUERY))
                : agentQuestions;
        String query = String.join(
                " ", questions.stream().map(MaterialRiskDto.ContractQuestion::question).toList());

        RagDto.SearchResult result = ragService.search(new RagDto.SearchRequest(
                query,
                new RagDto.SearchFilters(
                        erp.primaryContractId(), erp.primarySupplierId(), erp.materialId(), null, null),
                RAG_TOP_K));

        return new MaterialRiskDto.ContractEvidence(
                erp.erpMaterialId(), linkedContract(erp), questions, query,
                result.results().stream().map(MaterialRiskService::toClauseHit).toList(),
                result.mock());
    }

    /**
     * 검색 결과 청크에 조항 제목을 붙인다.
     *
     * <p>제목은 ChromaDB 청크에 없는 값이라 본문 머리에서 뽑는다. 그 규칙을 여기서 새로 짜지 않고
     * {@link ContractRagService.ClauseHeading}을 그대로 부른다 — 계약·RAG 화면이 이미 쓰는 것이라
     * 같은 조항이 두 화면에서 다른 제목으로 보이지 않는다. 정규식과 표제 매핑이라 LLM 호출은 없다.
     */
    private static MaterialRiskDto.ClauseHit toClauseHit(RagDto.SearchItem item) {
        ContractRagService.ClauseHeading heading =
                ContractRagService.ClauseHeading.parse(item.content());
        return new MaterialRiskDto.ClauseHit(
                item.documentId(),
                item.contractId(),
                item.supplierId(),
                item.materialId(),
                item.documentType(),
                item.chunkIndex(),
                item.pageNumber(),
                heading.clauseNo(),
                heading.displayTitle(),
                item.content(),
                item.similarityScore(),
                item.mockEmbedding());
    }

    // --- ERP Exposure Agent 호출 ---------------------------------------------------------------

    /** 자재 1건 평가. 실패해도 예외를 밖으로 던지지 않고 사유를 담아 돌려준다(목록이 죽지 않게). */
    private Assessment assess(MaterialRiskRepository.MaterialRow material, OffsetDateTime asOf) {
        try {
            ErpDto.ContextResponse erp = erpService.buildContext(
                    new ErpDto.ContextRequest(material.erpMaterialId(), null, asOf));
            // 스냅샷 시각은 Agent에 보내지 않지만 노후 판정과 화면 표기에 쓰므로 여기서 같이 읽는다.
            Instant snapshotAt = repository.findInventorySnapshotAt(erp.materialId(), asOf);
            return new Assessment(material, erp, callExposureAgent(erp, asOf), snapshotAt, null);
        } catch (BusinessException exception) {
            log.info("자재 {} ERP Context 구성 실패: {}", material.erpMaterialId(), exception.getMessage());
            return new Assessment(material, null, null, null, exception.getMessage());
        } catch (Exception exception) {
            log.warn("자재 {} ERP 노출도 계산 실패", material.erpMaterialId(), exception);
            return new Assessment(material, null, null, null,
                    "ERP 노출도 분석 서버에 연결할 수 없어 점수를 계산하지 못했습니다.");
        }
    }

    /**
     * 재고 스냅샷이 오래됐는지. 스냅샷 시각을 모르면 "판정 불가"라 false로 둔다 — 모르는 것을
     * 오래됐다고 단정하지 않는다.
     */
    private static boolean staleSnapshot(Instant snapshotAt, OffsetDateTime asOf) {
        return snapshotAt != null
                && Duration.between(snapshotAt, asOf.toInstant()).toHours() > INVENTORY_SNAPSHOT_MAX_AGE_HOURS;
    }

    /**
     * 화면에 내보낼 데이터 품질. Agent 판정과 Spring의 재고 노후 판정 중 <b>나쁜 쪽</b>을 쓴다.
     *
     * <p>Agent는 스냅샷 시각을 받지 않아 신선도를 볼 수 없으므로, 그 자리를 Spring이 메운다.
     */
    private static String dataQualityOf(ExposureResponse exposure, Instant snapshotAt, OffsetDateTime asOf) {
        String agentStatus = nullToUnknown(exposure.dataQualityStatus());
        if (!staleSnapshot(snapshotAt, asOf)) {
            return agentStatus;
        }
        return dataQualityRank(STALE) > dataQualityRank(agentStatus) ? STALE : agentStatus;
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

    /**
     * Agent 요청 조립. ERP에서 읽어오는 부분은 전부 {@link ErpExposureContextFactory}에 맡긴다 —
     * 멀티에이전트 브리핑과 같은 입력이어야 같은 자재가 같은 점수를 갖는다.
     */
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
                contextFactory.materialContext(erp),
                contextFactory.purchaseOrders(erp, asOf.toLocalDate()),
                contextFactory.requiredQuantity(erp),
                contextFactory.alternativeSuppliers(erp, asOf.toLocalDate()));
    }

    // --- 응답 조립 ------------------------------------------------------------------------------

    private static MaterialRiskDto.MaterialItem toItem(Assessment assessment, OffsetDateTime asOf) {
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
                ratio(erp.supplierDependencyRatio()),
                dataQualityOf(exposure, assessment.inventorySnapshotAt(), asOf),
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
            List<MaterialRiskDto.MaterialItem> items, OffsetDateTime asOf, OffsetDateTime newsAsOf) {
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

        // 나머지 KPI 3장과 같은 모집단(점수가 나온 자재)을 본다. 이 카드가 답하는 질문은 "지금
        // 화면에 뜬 숫자를 믿어도 되는가"이고, 그 숫자는 전부 평가된 자재에서 나오기 때문이다.
        //
        // 평가하지 못한 자재를 여기 섞지 않는다. 섞으면 그쪽이 늘 더 나쁜 값이라 카드가 거기에
        // 붙어버려 정작 평가된 자재들의 상태를 가린다 — 10종 중 1종이 결측이면 나머지 9종이
        // 신선하든 낡았든 카드는 똑같이 INCOMPLETE다. 그 1종은 unavailableCount와 자재별
        // unavailableReason으로 이미 드러나므로 여기서 한 번 더 겹쳐 말할 필요가 없다.
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
                asOf,
                newsAsOf);
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

    /**
     * 화면에 보여줄 경고. Agent가 남긴 것에 Spring의 재고 노후 판정을 더한다.
     *
     * <p>Agent의 "신선도를 확인하지 못했다"는 <b>보내지 않았기 때문에</b> 늘 붙는 문구라 그것만으론
     * 실제로 오래됐는지 알 수 없다. 며칠 지났는지는 Spring만 알고 있으므로 여기서 덧붙인다.
     */
    private static List<String> warnings(
            ExposureResponse exposure, Instant snapshotAt, OffsetDateTime asOf) {
        List<String> warnings = new ArrayList<>(nullToEmpty(exposure.warnings()));
        if (staleSnapshot(snapshotAt, asOf)) {
            long days = Duration.between(snapshotAt, asOf.toInstant()).toDays();
            warnings.add("재고 스냅샷이 " + days + "일 경과했습니다(기준 "
                    + INVENTORY_SNAPSHOT_MAX_AGE_HOURS + "시간). 재고 수치가 현재와 다를 수 있습니다.");
        }
        return List.copyOf(warnings);
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

    /**
     * 0~1 비율. 소수 4자리에서 자르되 뒤따르는 0은 떼어 낸다 — {@code 0.9100} 대신 {@code 0.91}로
     * 나가게 하려는 것이다. {@code stripTrailingZeros()}는 정수에서 지수 표기(1E+2)를 만들 수 있어
     * 음수 scale을 되돌린다({@code ErpService.normalize()}와 같은 처리).
     */
    private static BigDecimal ratio(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 자재 1건의 평가 결과. {@code failureReason}이 있으면 나머지 셋은 null이다.
     *
     * @param inventorySnapshotAt 계산에 쓰인 재고 스냅샷 시각. Agent에는 보내지 않고 노후 판정과
     *                            화면 표기에만 쓴다. 스냅샷이 없으면 null
     */
    private record Assessment(
            MaterialRiskRepository.MaterialRow material,
            ErpDto.ContextResponse erp,
            ExposureResponse exposure,
            Instant inventorySnapshotAt,
            String failureReason) {

        boolean failed() {
            return failureReason != null;
        }
    }

    /** 캐시된 개요 1건. */
    private record CachedOverview(MaterialRiskDto.Overview overview, Instant computedAt) {
        boolean isFresh() {
            return Duration.between(computedAt, Instant.now()).toSeconds() < OVERVIEW_CACHE_TTL_SECONDS;
        }
    }
}

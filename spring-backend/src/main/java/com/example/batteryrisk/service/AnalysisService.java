package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.AnalysisSupplierRecommendation;
import com.example.batteryrisk.dto.AnalysisDto;
import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.AnalysisNotFoundException;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.AnalysisSupplierRecommendationRepository;
import com.example.batteryrisk.repository.ErpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final Set<String> RISKY_SEVERITIES = Set.of("CRITICAL", "WARNING");

    /**
     * KG 게이트. <b>기본 true</b>이며 {@code @Value}·application.yml·docker-compose 세 곳이
     * 모두 일치해야 한다(risk-scoring.enabled와 같은 규칙).
     *
     * <p>false면 KG가 부족을 확정하지 않아도 멀티에이전트 브리핑을 자동 트리거한다. KG 서버가
     * 없는 개발·검증 환경에서 자동 경로를 끝까지 흘려보기 위한 스위치다.
     *
     * <p>FastAPI에도 같은 이름의 플래그가 있고 둘 다 내려야 자동 경로가 뚫린다 — Spring은
     * "멀티에이전트를 호출할지", FastAPI는 "그래프 안에서 KG 노드가 short-circuit할지"를 각각
     * 본다. 2026-08-03에 FastAPI만 내려둔 탓에 브리핑까지 도달하지 못한 적이 있다.
     */
    @Value("${app.kg-service.gate-enabled:true}")
    private boolean kgGateEnabled;

    /**
     * 자동 트리거된 멀티에이전트 브리핑에서 LLM 문장 생성을 쓸지. <b>기본 true</b>.
     *
     * <p>원래 {@code false}로 박혀 있었다. 그러면 response 노드가 규칙 기반 문장만 만들고
     * {@code BRIEFING_USE_CLAUDE}(Claude/OpenAI 선택)는 아무 효력이 없다 — 그 플래그는
     * {@code use_llm=true}일 때 provider를 고르는 것이라, 자동 경로에서는 도달조차 못 했다.
     *
     * <p>여기까지 오려면 {@code COLLECTION_ANALYSIS_ENABLED=true}로 이미 LLM 비용을 감수하기로
     * 한 상태이고, RISKY_SEVERITIES·자재추출·KG 세 게이트를 통과한 소수 건이라 기본을 true로 둔다.
     * 비용을 막아야 하면 이 플래그만 내리면 되고, 그때도 브리핑 자체는 규칙 기반으로 계속 나온다.
     */
    @Value("${app.multi-agent.auto-briefing-use-llm:true}")
    private boolean autoBriefingUseLlm;

    private final RestClient fastApiRestClient;
    private final AnalysisRepository analysisRepository;
    private final AnalysisSupplierRecommendationRepository supplierRecommendationRepository;
    private final ErpExposureRequestService erpExposureRequestService;
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;
    private final AiBriefingService aiBriefingService;
    private final ErpRepository erpRepository;

    public AnalysisService(
            RestClient fastApiRestClient, AnalysisRepository analysisRepository,
            AnalysisSupplierRecommendationRepository supplierRecommendationRepository,
            ErpExposureRequestService erpExposureRequestService,
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            AiBriefingService aiBriefingService, ErpRepository erpRepository) {
        this.fastApiRestClient = fastApiRestClient;
        this.analysisRepository = analysisRepository;
        this.supplierRecommendationRepository = supplierRecommendationRepository;
        this.erpExposureRequestService = erpExposureRequestService;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.aiBriefingService = aiBriefingService;
        this.erpRepository = erpRepository;
    }

    public AnalysisDto.AnalysisResponse create(AnalysisDto.AnalyzeRequest request) {
        Analysis analysis = Analysis.pending(
                request.materialId(), request.supplierId(), request.eventTitle(),
                request.eventContent(), request.sourceName(), request.countryCode(), request.sourceUrl());
        analysisRepository.saveAndFlush(analysis);

        analysis.markProcessing();
        analysisRepository.saveAndFlush(analysis);

        try {
            AnalysisDto.FastApiAnalyzeData data = requestFastApiAnalysis(
                    analysis, request.featureOverrides(), request.extractionOverride());
            analysis.markCompleted(
                    data.classification().impactDomain(), data.classification().confidence(),
                    data.severity().severity(), data.severity().score(),
                    String.join(",", data.severity().reasonCodes()), data.severity().ruleVersion(),
                    data.mock());
            applySignalDetail(analysis, data);

            boolean hasMaterial = data.affectedMaterials() != null && !data.affectedMaterials().isEmpty();
            String materialCategory = hasMaterial ? data.affectedMaterials().get(0) : null;

            // 사실: 등급과 무관하게 저장한다. LLM은 모든 기사에서 자재를 뽑아주는데, 예전엔
            // 이 값이 attachSupplierRecommendation(조치, RISKY_SEVERITIES 게이트 안쪽)에서만
            // 채워져서 NORMAL 등급 뉴스는 자재 정보 자체가 사라졌다.
            if (materialCategory != null) {
                analysis.attachMaterialCategory(materialCategory);
            }

            // 조치: 위험 등급일 때만 (기존 조건 유지) — 공급사 추천 호출·Chain B 자동 트리거는
            // 비용이 드는 부가 작업이라 여기 계속 좁혀둔다.
            if (RISKY_SEVERITIES.contains(data.severity().severity()) && materialCategory != null) {
                ErpExposureContext erpContext = fetchErpExposureContext(analysis, data, materialCategory);
                AnalysisDto.SupplierRecommendationSummary recommendation = fetchSupplierRecommendation(
                        materialCategory, erpContext.materialRiskScore(), erpContext.supplierRiskScores());
                if (recommendation != null) {
                    analysis.attachSupplierRecommendation(materialCategory, recommendation.caveats());
                    persistSupplierRecommendations(analysis.getAnalysisId(), recommendation.recommendations());
                }
                // 게이트를 내렸으면 KG 확정 없이도 태운다. 그 사실은 FastAPI가 응답 warnings에
                // 남기므로(KG 게이트 우회 문구) 결과만 보고도 근거 없는 지목인지 구분할 수 있다.
                if (!kgGateEnabled || erpContext.kgShortageConfirmed()) {
                    triggerMultiAgentBriefingSafely(analysis, data, erpContext, materialCategory);
                }
            }

            // F10 즉시 알림은 외부 뉴스 severity가 아니라 최종 합성 등급 기준으로 바뀌었다(V33).
            // 트리거는 합성 평가가 저장되는 MultiAgentOrchestrationService.persist()로 옮겨졌다 —
            // 합성 등급은 멀티에이전트가 돈 뒤에만 존재하기 때문이다.
        } catch (RuntimeException exception) {
            log.warn("FastAPI 분석 호출에 실패하여 분석을 FAILED 처리합니다. analysisId={}", analysis.getAnalysisId(), exception);
            analysis.markFailed("FASTAPI_ANALYZE_FAILED", "FastAPI 분석 호출에 실패했습니다.");
        }
        analysisRepository.saveAndFlush(analysis);

        return toResponse(analysis);
    }

    /**
     * 점수의 입력값(외부신호 상세)과 한국어 요약을 분석 행에 남긴다 — 리스크 모니터링 상세 화면이
     * "왜 이 점수인가"를 보여주려면 점수와 입력값이 같은 행에 있어야 한다.
     *
     * <p>tone은 {@code features}(=severity가 실제로 먹은 값)에서 가져오고 {@code extraction}은 폴백이다.
     * 둘은 다를 수 있다 — orchestration_service가 feature_overrides에 tone이 없을 때만 추출 tone으로
     * 덮어쓰므로, 화면에는 점수를 만든 쪽을 보여주는 편이 맞다.
     */
    private static void applySignalDetail(Analysis analysis, AnalysisDto.FastApiAnalyzeData data) {
        AnalysisDto.ExtractionOverride extraction = data.extraction();
        AnalysisDto.FastApiFeatureVector features = data.features();
        if (extraction == null && features == null) {
            return;
        }
        Double toneScore = features != null && features.toneScore() != null
                ? features.toneScore()
                : (extraction == null ? null : extraction.toneScore());
        analysis.applySignalDetail(
                extraction == null ? null : extraction.summaryKr(),
                toneScore,
                features == null ? null : features.newsCount(),
                features == null ? null : features.goldsteinScale());
    }

    private void persistSupplierRecommendations(
            UUID analysisId, List<AnalysisDto.RankedSupplierRecommendation> recommendations) {
        List<AnalysisSupplierRecommendation> entities = recommendations.stream()
                .map(r -> AnalysisSupplierRecommendation.of(
                        analysisId, r.supplierId(), r.supplierCode(), r.supplierName(),
                        r.rank(), r.pros(), r.cons(), r.recommendationReason()))
                .toList();
        supplierRecommendationRepository.saveAll(entities);
    }

    public AnalysisDto.AnalysisResponse get(String analysisId) {
        UUID id;
        try {
            id = UUID.fromString(analysisId);
        } catch (IllegalArgumentException exception) {
            throw new AnalysisNotFoundException(analysisId);
        }
        Analysis analysis = analysisRepository.findById(id)
                .orElseThrow(() -> new AnalysisNotFoundException(analysisId));
        return toResponse(analysis);
    }

    /**
     * Chain A -&gt; Chain B 자동 트리거(2026-08-01 신설). KG가 재고부족을 확정한 경우에만
     * 멀티에이전트(LangGraph) 브리핑 생성을 자동으로 호출한다 — 매칭 없음/재고충분 뉴스까지
     * 전부 태우면 비싼 LLM 다단계가 낭비되므로, KG 게이트를 통과한 것만 자동화한다.
     * 실패해도 분석 생성 자체를 막으면 안 되므로 여기서 흡수한다.
     */
    private void triggerMultiAgentBriefingSafely(
            Analysis analysis, AnalysisDto.FastApiAnalyzeData data, ErpExposureContext erpContext,
            String materialCategory) {
        String erpMaterialId = resolveErpMaterialId(erpContext, materialCategory);
        if (erpMaterialId == null) {
            log.warn("멀티에이전트 브리핑 건너뜀 — ERP 자재를 정하지 못했습니다. analysisId={} materialCategory={}",
                    analysis.getAnalysisId(), materialCategory);
            return;
        }
        try {
            // FastAPI MultiAgentBriefingRequest의 summary_kr/article_text는 Optional이 아니라
            // 기본값 ""인 str 필드라, Jackson이 null을 그대로 보내면(explicit null) pydantic이
            // 422로 거부한다(실제 Docker 검증 중 발견) — 빈 문자열로 채워야 한다.
            // analysisId는 null로 둔다: 이 시점엔 analysis가 아직 COMPLETED로 flush되지 않아
            // (saveAndFlush는 create()의 이 호출 이후에 일어난다) PR#11의 analysisId 경로를 타면
            // ANALYSIS_NOT_SCORED로 막힌다. 기존처럼 값을 직접 실어 보낸다.
            MultiAgentDto.GenerateRequest request = new MultiAgentDto.GenerateRequest(
                    analysis.getAnalysisId().toString(), null, analysis.getEventTitle(),
                    analysis.getEventContent() != null ? analysis.getEventContent() : "", "",
                    data.classification().impactDomain(), data.classification().impactDomain(),
                    data.severity().severity(), (int) Math.round(data.severity().score()),
                    erpMaterialId, erpContext.resolvedErpSupplierId(),
                    analysis.getCountryCode(), OffsetDateTime.now(), autoBriefingUseLlm);
            MultiAgentDto.Response response = multiAgentOrchestrationService.generate(request);

            // 점수는 generate()가 procurement_risk_assessments에 남긴다. 본문은 V29부터
            // ai_briefings가 정본이라 여기서 남긴다 — 그러지 않으면 자동 생성분만 AI 브리핑
            // 화면 목록에서 통째로 빠진다(V29 이전에 실제로 그랬다).
            aiBriefingService.recordAutoGenerated(
                    analysis, erpMaterialId, erpContext.resolvedErpSupplierId(),
                    materialCategory, response);
        } catch (RuntimeException exception) {
            log.warn("멀티에이전트 브리핑 자동 생성 실패 (analysisId={}): {}",
                    analysis.getAnalysisId(), exception.getMessage());
        }
    }

    /**
     * 브리핑 요청에 실을 ERP 자재를 정한다. KG가 확정한 자재를 우선 쓰고, 없으면 대분류를 실제 ERP
     * 자재로 펼쳐 첫 번째를 쓴다.
     *
     * <p>폴백이 필요한 이유: KG 게이트를 내리면(kgGateEnabled=false) KG 서버 없이도 여기까지 오는데,
     * {@code resolvedErpMaterialId}는 그 KG 응답에서만 채워지므로 null이다. 그대로 보내면
     * GenerateRequest의 {@code @NotBlank}에 걸려 NPE로 죽는다 — 게이트만 내리고 이걸 안 하면
     * "게이트는 열렸는데 브리핑은 여전히 0건"이 된다(2026-08-03 실측으로 확인).
     *
     * <p>펼치는 방식은 구매 리스크 스케줄러(MultiAgentOrchestrationService)가 쓰는 것과 같은
     * 조회다. 스케줄러는 대분류에 속한 ERP 자재를 <b>전부</b> 도는 반면 여기서는 첫 건만 쓴다 —
     * 자동 트리거는 기사 1건당 브리핑 1건이 맞고, LLM 비용도 배수로 늘지 않는다. 대분류당 자재는
     * 1~2개이고 2개인 건 LITHIUM(탄산/수산화)·GRAPHITE(천연/인조)뿐이다.
     */
    private String resolveErpMaterialId(ErpExposureContext erpContext, String materialCategory) {
        String resolved = erpContext.resolvedErpMaterialId();
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        List<String> candidates = erpRepository.findActiveErpMaterialIds(materialCategory);
        if (candidates.isEmpty()) {
            return null;
        }
        String fallback = candidates.get(0);
        // KG가 고른 게 아니라 대분류에서 임의로 집은 값이라는 걸 로그에 남긴다 — 브리핑이 왜 이 자재를
        // 지목했는지 나중에 되짚을 수 있어야 한다.
        log.info("KG 미확정 — 대분류 {}의 ERP 자재 {}건 중 {}로 브리핑을 생성합니다.",
                materialCategory, candidates.size(), fallback);
        return fallback;
    }

    /** severity가 CRITICAL/WARNING일 때만 호출됩니다. */
    private AnalysisDto.SupplierRecommendationSummary fetchSupplierRecommendation(
            String materialCategory, Double erpAlternativeSupplierRiskScore, Map<String, Double> erpSupplierRiskScores) {
        try {
            AnalysisDto.FastApiSupplierRecommendResponse response = fastApiRestClient.post()
                    .uri("/api/v1/suppliers/recommend")
                    .body(new AnalysisDto.SupplierRecommendRequest(
                            materialCategory, erpAlternativeSupplierRiskScore, erpSupplierRiskScores))
                    .retrieve()
                    .body(AnalysisDto.FastApiSupplierRecommendResponse.class);
            return response != null ? response.data() : null;
        } catch (RuntimeException exception) {
            log.warn("대체 공급사 추천 호출 실패 (material_category={}): {}", materialCategory, exception.getMessage());
            return null;
        }
    }

    /**
     * ERP Exposure Agent 호출 결과에서 F9가 쓸 위험점수(자재 단위 점수 + 공급사별 점수 맵)와,
     * Chain B 자동 트리거 판단에 쓸 KG 매칭 정보(재고부족 확정 여부·확정 시 공급사/자재 ERP ID)를
     * 함께 담습니다.
     */
    private record ErpExposureContext(
            Double materialRiskScore, Map<String, Double> supplierRiskScores,
            boolean kgShortageConfirmed, String resolvedErpSupplierId, String resolvedErpMaterialId) {
        static final ErpExposureContext EMPTY = new ErpExposureContext(null, Map.of(), false, null, null);
    }

    /**
     * F9의 위험 평가를 우리 자체 risk_level 대신 ERP Exposure Agent의 계산 결과로 대체하기 위한 호출입니다.
     * 실패해도 F9 추천 자체는 계속 진행해야 하므로 빈 컨텍스트를 반환하고 흡수합니다.
     */
    private ErpExposureContext fetchErpExposureContext(
            Analysis analysis, AnalysisDto.FastApiAnalyzeData data, String materialCategory) {
        try {
            ErpExposureDto.ExposureTriggerRequest trigger = new ErpExposureDto.ExposureTriggerRequest(
                    analysis.getAnalysisId().toString(), analysis.getAnalysisId().toString(), materialCategory,
                    data.classification().impactDomain(), data.severity().score(), data.severity().severity(),
                    analysis.getCountryCode(), analysis.getEventTitle());
            ErpExposureRequestService.ErpExposureAnalysisResult outcome =
                    erpExposureRequestService.analyzeExposure(trigger);
            ErpExposureDto.ExposureResponse response = outcome.response();

            Object riskScore = response.riskComponents() != null
                    ? response.riskComponents().get("alternativeSupplierRiskScore") : null;
            Double materialRiskScore = riskScore instanceof Number number ? number.doubleValue() : null;

            Map<String, Double> supplierRiskScores = new HashMap<>();
            if (response.supplierAssessments() != null) {
                for (ErpExposureDto.SupplierAssessment assessment : response.supplierAssessments()) {
                    if (assessment.supplierId() != null && assessment.supplierRiskScore() != null) {
                        supplierRiskScores.put(assessment.supplierId(), assessment.supplierRiskScore());
                    }
                }
            }
            return new ErpExposureContext(materialRiskScore, supplierRiskScores,
                    outcome.kgShortageConfirmed(), outcome.resolvedErpSupplierId(), outcome.resolvedErpMaterialId());
        } catch (RuntimeException exception) {
            log.warn("ERP Exposure Agent 호출 실패 (material_category={}): {}", materialCategory, exception.getMessage());
            return ErpExposureContext.EMPTY;
        }
    }

    private AnalysisDto.FastApiAnalyzeData requestFastApiAnalysis(
            Analysis analysis, AnalysisDto.FeatureOverrides overrides,
            AnalysisDto.ExtractionOverride extractionOverride) {
        AnalysisDto.FastApiEvent event = new AnalysisDto.FastApiEvent(
                analysis.getAnalysisId().toString(), analysis.getEventTitle(), analysis.getEventContent(),
                analysis.getSourceName() != null ? analysis.getSourceName() : "SPRING",
                analysis.getSourceUrl(), Instant.now().toString(), analysis.getCountryCode());
        AnalysisDto.FastApiFeatureOverrides fastApiOverrides = overrides == null ? null
                : new AnalysisDto.FastApiFeatureOverrides(
                        overrides.goldsteinScale(), overrides.newsCount(),
                        overrides.gdacsAlertLevel(), overrides.stockVolatility20d(), overrides.bdiIndex());
        AnalysisDto.FastApiAnalyzeRequest fastApiRequest =
                new AnalysisDto.FastApiAnalyzeRequest(event, fastApiOverrides, extractionOverride);

        AnalysisDto.FastApiAnalyzeResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/analyze")
                    .body(fastApiRequest)
                    .retrieve()
                    .body(AnalysisDto.FastApiAnalyzeResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI analyze 응답 오류: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new IllegalStateException("FastAPI analyze 호출이 오류 응답을 반환했습니다.", exception);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new IllegalStateException("FastAPI analyze 응답이 올바르지 않습니다.");
        }
        return response.data();
    }

    private AnalysisDto.AnalysisResponse toResponse(Analysis analysis) {
        List<String> reasonCodes = analysis.getReasonCodes() == null || analysis.getReasonCodes().isBlank()
                ? List.of() : List.of(analysis.getReasonCodes().split(","));
        return new AnalysisDto.AnalysisResponse(
                analysis.getAnalysisId().toString(), analysis.getStatus(), analysis.getSourceUrl(),
                analysis.getImpactDomain(), analysis.getConfidence(), analysis.getSeverity(), analysis.getSeverityScore(),
                reasonCodes, analysis.getRuleVersion(), analysis.isMock(),
                analysis.getErrorCode(), analysis.getErrorMessage(), analysis.getCreatedAt(),
                analysis.getCompletedAt(), buildSupplierRecommendationSummary(analysis));
    }

    private AnalysisDto.SupplierRecommendationSummary buildSupplierRecommendationSummary(Analysis analysis) {
        if (analysis.getMaterialCategory() == null) {
            return null;
        }
        List<AnalysisSupplierRecommendation> rows =
                supplierRecommendationRepository.findByAnalysisIdOrderByRankPositionAsc(analysis.getAnalysisId());
        if (rows.isEmpty()) {
            return null;
        }
        List<AnalysisDto.RankedSupplierRecommendation> recommendations = rows.stream()
                .map(r -> new AnalysisDto.RankedSupplierRecommendation(
                        r.getSupplierId(), r.getSupplierCode(), r.getSupplierName(), r.getRankPosition(),
                        r.getPros(), r.getCons(), r.getRecommendationReason()))
                .toList();
        List<String> caveats = analysis.getRecommendationCaveats() == null || analysis.getRecommendationCaveats().isBlank()
                ? List.of() : List.of(analysis.getRecommendationCaveats().split("\n"));
        return new AnalysisDto.SupplierRecommendationSummary(analysis.getMaterialCategory(), recommendations, caveats);
    }
}

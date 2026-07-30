package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.AnalysisSupplierRecommendation;
import com.example.batteryrisk.dto.AnalysisDto;
import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.AnalysisNotFoundException;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.AnalysisSupplierRecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final Set<String> RISKY_SEVERITIES = Set.of("CRITICAL", "WARNING");

    private final RestClient fastApiRestClient;
    private final AnalysisRepository analysisRepository;
    private final AnalysisSupplierRecommendationRepository supplierRecommendationRepository;
    private final NotificationService notificationService;
    private final ErpExposureRequestService erpExposureRequestService;

    public AnalysisService(
            RestClient fastApiRestClient, AnalysisRepository analysisRepository,
            AnalysisSupplierRecommendationRepository supplierRecommendationRepository,
            NotificationService notificationService, ErpExposureRequestService erpExposureRequestService) {
        this.fastApiRestClient = fastApiRestClient;
        this.analysisRepository = analysisRepository;
        this.supplierRecommendationRepository = supplierRecommendationRepository;
        this.notificationService = notificationService;
        this.erpExposureRequestService = erpExposureRequestService;
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

            if (RISKY_SEVERITIES.contains(data.severity().severity())
                    && data.affectedMaterials() != null && !data.affectedMaterials().isEmpty()) {
                String materialCategory = data.affectedMaterials().get(0);
                ErpExposureContext erpContext = fetchErpExposureContext(analysis, data, materialCategory);
                AnalysisDto.SupplierRecommendationSummary recommendation = fetchSupplierRecommendation(
                        materialCategory, erpContext.materialRiskScore(), erpContext.supplierRiskScores());
                if (recommendation != null) {
                    analysis.attachSupplierRecommendation(materialCategory, recommendation.caveats());
                    persistSupplierRecommendations(analysis.getAnalysisId(), recommendation.recommendations());
                }
            }

            if ("CRITICAL".equals(data.severity().severity())) {
                notifyCriticalSafely(analysis);
            }
        } catch (RuntimeException exception) {
            log.warn("FastAPI 분석 호출에 실패하여 분석을 FAILED 처리합니다. analysisId={}", analysis.getAnalysisId(), exception);
            analysis.markFailed("FASTAPI_ANALYZE_FAILED", "FastAPI 분석 호출에 실패했습니다.");
        }
        analysisRepository.saveAndFlush(analysis);

        return toResponse(analysis);
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

    /** F10: CRITICAL 즉시 알림. 발송 실패가 분석 생성 자체를 실패시키면 안 되므로 여기서 흡수합니다. */
    private void notifyCriticalSafely(Analysis analysis) {
        try {
            notificationService.notifyCritical(analysis);
        } catch (RuntimeException exception) {
            log.warn("CRITICAL 알림 발송 처리 중 오류 (analysisId={}): {}", analysis.getAnalysisId(), exception.getMessage());
        }
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

    /** ERP Exposure Agent 호출 결과에서 F9가 쓸 위험점수를 담습니다: 자재 단위 점수 하나 + 공급사별(supplierId=supplier_code) 점수 맵. */
    private record ErpExposureContext(Double materialRiskScore, Map<String, Double> supplierRiskScores) {
        static final ErpExposureContext EMPTY = new ErpExposureContext(null, Map.of());
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
            ErpExposureDto.ExposureResponse response = erpExposureRequestService.analyzeExposure(trigger);

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
            return new ErpExposureContext(materialRiskScore, supplierRiskScores);
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

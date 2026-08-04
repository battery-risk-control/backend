package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** F3 분석 요청·오케스트레이션에서 사용하는 외부·내부 통신 DTO 모음입니다. */
public final class AnalysisDto {
    private AnalysisDto() {}

    public record FeatureOverrides(
            @JsonProperty("goldstein_scale") Double goldsteinScale,
            @JsonProperty("news_count") Integer newsCount,
            @JsonProperty("gdacs_alert_level") Integer gdacsAlertLevel,
            @JsonProperty("stock_volatility_20d") Double stockVolatility20d,
            @JsonProperty("bdi_index") Double bdiIndex
    ) {}

    /** FastAPI ExtractionResult와 동일한 필드 구성 — 이미 완료된 추출 결과를 /analyze로 그대로 전달할 때 사용합니다. */
    public record ExtractionOverride(
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("affected_materials") List<String> affectedMaterials,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("tone_score") Double toneScore,
            @JsonProperty("impact_domain_draft") String impactDomainDraft,
            @JsonProperty("summary_kr") String summaryKr,
            @JsonProperty("is_supply_chain_relevant") Boolean isSupplyChainRelevant,
            @JsonProperty("extraction_model_version") String extractionModelVersion,
            Boolean mock
    ) {}

    public record AnalyzeRequest(
            @JsonProperty("material_id") Long materialId,
            @JsonProperty("supplier_id") Long supplierId,
            @NotBlank(message = "이벤트 제목을 입력해 주세요.")
            @JsonProperty("event_title") String eventTitle,
            @NotBlank(message = "이벤트 본문을 입력해 주세요.")
            @JsonProperty("event_content") String eventContent,
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("feature_overrides") FeatureOverrides featureOverrides,
            @JsonProperty("extraction_override") ExtractionOverride extractionOverride
    ) {
        public AnalyzeRequest(
                Long materialId, Long supplierId, String eventTitle, String eventContent,
                String sourceName, String countryCode) {
            this(materialId, supplierId, eventTitle, eventContent, sourceName, countryCode, null, null, null);
        }

        public AnalyzeRequest(
                Long materialId, Long supplierId, String eventTitle, String eventContent,
                String sourceName, String countryCode, FeatureOverrides featureOverrides) {
            this(materialId, supplierId, eventTitle, eventContent, sourceName, countryCode, null, featureOverrides, null);
        }
    }

    public record AnalysisResponse(
            @JsonProperty("analysis_id") String analysisId,
            String status,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("impact_domain") String impactDomain,
            Double confidence,
            String severity,
            @JsonProperty("severity_score") Double severityScore,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("rule_version") String ruleVersion,
            boolean mock,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("completed_at") Instant completedAt,
            @JsonProperty("supplier_recommendation") SupplierRecommendationSummary supplierRecommendation
    ) {}

    /** severity가 CRITICAL/WARNING일 때만 채워지는 F9 대체 공급사 추천 결과입니다(분석 생성 응답에만 실림, 영구저장 안 함). */
    public record SupplierRecommendationSummary(
            @JsonProperty("material_category") String materialCategory,
            List<RankedSupplierRecommendation> recommendations,
            List<String> caveats
    ) {}

    public record RankedSupplierRecommendation(
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("supplier_code") String supplierCode,
            @JsonProperty("supplier_name") String supplierName,
            int rank,
            List<String> pros,
            List<String> cons,
            @JsonProperty("recommendation_reason") String recommendationReason
    ) {}

    /** FastAPI POST /api/v1/suppliers/recommend 요청 바디입니다. */
    public record SupplierRecommendRequest(
            @JsonProperty("material_category") String materialCategory,
            @JsonProperty("erp_alternative_supplier_risk_score") Double erpAlternativeSupplierRiskScore,
            @JsonProperty("erp_supplier_risk_scores") Map<String, Double> erpSupplierRiskScores) {}

    public record FastApiSupplierRecommendResponse(boolean success, SupplierRecommendationSummary data) {}

    /** FastAPI POST /api/v1/analyze 요청 바디입니다. FastAPI 스펙은 event를 중첩 객체로 받습니다. */
    public record FastApiAnalyzeRequest(
            FastApiEvent event,
            @JsonProperty("feature_overrides") FastApiFeatureOverrides featureOverrides,
            @JsonProperty("extraction_override") ExtractionOverride extractionOverride
    ) {}

    public record FastApiEvent(
            @JsonProperty("external_event_id") String externalEventId,
            String title,
            String content,
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("country_code") String countryCode
    ) {}

    public record FastApiFeatureOverrides(
            @JsonProperty("goldstein_scale") Double goldsteinScale,
            @JsonProperty("news_count") Integer newsCount,
            @JsonProperty("gdacs_alert_level") Integer gdacsAlertLevel,
            @JsonProperty("stock_volatility_20d") Double stockVolatility20d,
            @JsonProperty("bdi_index") Double bdiIndex
    ) {}

    public record FastApiAnalyzeResponse(boolean success, FastApiAnalyzeData data, Instant timestamp) {}

    public record FastApiAnalyzeData(
            FastApiClassification classification,
            FastApiSeverity severity,
            boolean mock,
            @JsonProperty("affected_materials") List<String> affectedMaterials,
            // 아래 둘은 FastAPI가 예전부터 내려주던 블록인데 Spring이 읽지 않아 버려지고 있었다.
            // 리스크 모니터링 상세의 "뉴스 요약"·"외부신호" 패널이 이 값들을 쓴다(analyses에 저장).
            ExtractionOverride extraction,
            FastApiFeatureVector features
    ) {}

    /**
     * severity 계산에 실제로 쓰인 피처 벡터(FastAPI {@code FeatureVector}).
     *
     * <p>요청의 {@code feature_overrides}와 값이 다를 수 있다 — FastAPI가 override와 자체 수집값을
     * 병합하고 비면 기본값을 채우기 때문이다. 화면에는 <b>점수를 만든 쪽</b>인 이 값을 보여준다.
     */
    public record FastApiFeatureVector(
            @JsonProperty("goldstein_scale") Double goldsteinScale,
            @JsonProperty("news_count") Integer newsCount,
            @JsonProperty("tone_score") Double toneScore
    ) {}

    public record FastApiClassification(
            @JsonProperty("impact_domain") String impactDomain,
            // nullable — impact_domain을 LLM 추출에서 직결로 받는 경로에는 분류 확률이 없어 null이 온다.
            // 원시 double로 두면 Jackson이 null을 0.0으로 뭉개 "신뢰도 0%"와 "신뢰도 미상"이 구분되지 않는다.
            Double confidence,
            @JsonProperty("model_version") String modelVersion,
            boolean mock
    ) {}

    public record FastApiSeverity(
            String severity,
            double score,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("rule_version") String ruleVersion,
            boolean mock
    ) {}
}

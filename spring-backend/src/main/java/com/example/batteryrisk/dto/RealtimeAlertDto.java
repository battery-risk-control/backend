package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 조장 승인 실시간 관제 공통 스키마를 표현하는 Spring 공개 API DTO입니다. */
public final class RealtimeAlertDto {
    private RealtimeAlertDto() {}

    public record RealtimeAlertsResponse(
            Instant timestamp,
            List<RealtimeAlert> alerts
    ) {}

    public record RealtimeAlert(
            @JsonProperty("event_id") long eventId,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("country_name_kr") String countryNameKr,
            @Schema(description = "[경도, 위도] 순서의 지도 좌표", example = "[113.9213,-0.7893]")
            List<Double> coordinates,
            @JsonProperty("affected_materials") List<String> affectedMaterials,
            @JsonProperty("news_info") NewsInfo newsInfo,
            @JsonProperty("risk_assessment") RiskAssessment riskAssessment
    ) {}

    public record NewsInfo(
            String title,
            @JsonProperty("impact_domain")
            @Schema(description = "LLM 또는 Mock이 만든 1차 영향 도메인") String impactDomain,
            @JsonProperty("summary_kr") String summaryKr,
            String url
    ) {}

    public record RiskAssessment(
            @JsonProperty("final_level")
            @Schema(description = "High(심각), Medium(주의), Low(정상)") String finalLevel,
            @JsonProperty("ai_evidence") AiEvidence aiEvidence
    ) {}

    /** event_features 테이블과 1:1 매핑되는 모델 입력 Feature입니다. */
    public record AiEvidence(
            @JsonProperty("tone_score") double toneScore,
            @JsonProperty("goldstein_scale") double goldsteinScale,
            @JsonProperty("news_count") int newsCount,
            @JsonProperty("country_is_mining_hub")
            @Schema(description = "주요 광물 생산 거점이면 1, 아니면 0") int countryIsMiningHub,
            @JsonProperty("rainfall_24h_mm") double rainfall24hMm,
            @JsonProperty("gdacs_alert_level") int gdacsAlertLevel,
            @JsonProperty("actor1_type") String actor1Type,
            @JsonProperty("actor2_type") String actor2Type,
            @JsonProperty("stock_volatility_20d")
            @Schema(description = "최근 20일 변동성 백분율. 14.5는 14.5%") double stockVolatility20d
    ) {}
}

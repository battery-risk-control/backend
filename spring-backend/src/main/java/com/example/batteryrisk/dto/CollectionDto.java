package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** F4 외부 데이터 수집에서 사용하는 어댑터 결과·실행 결과 DTO 모음입니다. */
public final class CollectionDto {
    private CollectionDto() {}

    /** 데이터 소스 어댑터가 한 번의 수집으로 만들어내는 표준화된 원시 항목입니다. */
    public record CollectedItem(
            String externalId,
            String title,
            String content,
            String sourceUrl,
            String countryCode,
            String payloadJson,
            String newCursorValue,
            Double goldsteinScale
    ) {}

    public record CollectionRunResult(
            String source,
            @JsonProperty("data_type") String dataType,
            int collected,
            @JsonProperty("new_items") int newItems,
            @JsonProperty("analyses_triggered") int analysesTriggered,
            String status,
            @JsonProperty("error_message") String errorMessage
    ) {}

    public record CollectionSummary(List<CollectionRunResult> results) {}

    /** GDELT 실시간 수집이 막혀있을 때 F4→F3 파이프라인을 검증하기 위한 테스트 전용 요청/응답입니다. */
    public record TestNewsRequest(
            String title,
            String content,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("source_url") String sourceUrl,

            // 생략하면 null — severity 공식(base_score = 70×norm_goldstein + 30×article_signal)이
            // norm_goldstein을 중립값으로 취급해 텍스트가 아무리 자극적이어도 severity가 NORMAL
            // 근처에 머문다. 실제 GDELT 사례(backend 저장소 data_core/event_features_normalized.csv)의
            // 값을 넣으면 실제 파이프라인과 동일한 조건으로 WARNING/CRITICAL 게이트를 테스트할 수 있다.
            @JsonProperty("goldstein_scale") Double goldsteinScale
    ) {}

    public record TestNewsResult(
            @JsonProperty("raw_event_id") Long rawEventId,
            @JsonProperty("analysis_id") String analysisId,
            String status
    ) {}

    /** FastAPI POST /api/v1/internal/realtime-pipeline/fetch-and-triage 연동 DTO. */
    public record RealtimeFetchRequest(@JsonProperty("cursor_value") String cursorValue) {}
    public record DemoReplayRequest(int limit) {}

    public record RealtimeCandidate(
            @JsonProperty("global_event_id") String globalEventId,
            String title,
            /** 데모 매니페스트에 미리 번역해 둔 한국어 제목. 없으면 null → 주입 시 영문 제목으로 폴백. */
            @JsonProperty("title_kr") String titleKr,
            String content,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("action_geo_country_code") String actionGeoCountryCode,
            @JsonProperty("goldstein_scale") Double goldsteinScale,
            @JsonProperty("num_articles") Integer numArticles,
            @JsonProperty("avg_tone") Double avgTone,
            @JsonProperty("original_event_date") String originalEventDate,
            @JsonProperty("demo_day") Integer demoDay,
            @JsonProperty("material_enum") String materialEnum,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("tone_score") Double toneScore,
            @JsonProperty("impact_domain") String impactDomain
    ) {}

    public record RealtimeFetchResult(
            List<RealtimeCandidate> items,
            @JsonProperty("new_cursor_value") String newCursorValue
    ) {}

    public record FastApiRealtimeFetchResponse(boolean success, RealtimeFetchResult data) {}
}

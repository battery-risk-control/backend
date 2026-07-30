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
            @JsonProperty("source_url") String sourceUrl
    ) {}

    public record TestNewsResult(
            @JsonProperty("raw_event_id") Long rawEventId,
            @JsonProperty("analysis_id") String analysisId,
            String status
    ) {}

    /** FastAPI POST /api/v1/internal/realtime-pipeline/fetch-and-triage 연동 DTO. */
    public record RealtimeFetchRequest(@JsonProperty("cursor_value") String cursorValue) {}

    public record RealtimeCandidate(
            @JsonProperty("global_event_id") String globalEventId,
            String title,
            String content,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("action_geo_country_code") String actionGeoCountryCode,
            @JsonProperty("goldstein_scale") Double goldsteinScale
    ) {}

    public record RealtimeFetchResult(
            List<RealtimeCandidate> items,
            @JsonProperty("new_cursor_value") String newCursorValue
    ) {}

    public record FastApiRealtimeFetchResponse(boolean success, RealtimeFetchResult data) {}
}

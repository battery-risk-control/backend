package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** FastAPI 자세한 요약 생성기(/api/v1/internal/llm/summarize) 호출 DTO. */
public final class SummaryDto {
    private SummaryDto() {}

    public record SummarizeRequest(String title, String content) {}

    public record SummarizeResponse(boolean success, SummarizeData data) {}

    public record SummarizeData(
            @JsonProperty("summary_kr") String summaryKr,
            Boolean mock,
            @JsonProperty("model_version") String modelVersion
    ) {}
}

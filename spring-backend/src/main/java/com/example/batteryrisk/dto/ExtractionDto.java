package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** FastAPI mock LLM 추출기(/api/v1/internal/llm/extract) 호출에서 쓰는 DTO 모음입니다. */
public final class ExtractionDto {
    private ExtractionDto() {}

    public record ExtractRequest(String title, String content, @JsonProperty("country_code") String countryCode) {}

    public record ExtractResponse(boolean success, ExtractData data) {}

    public record ExtractData(
            @JsonProperty("affected_materials") List<String> affectedMaterials,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("impact_domain_draft") String impactDomainDraft,
            @JsonProperty("tone_score") Double toneScore,
            @JsonProperty("summary_kr") String summaryKr
    ) {}
}

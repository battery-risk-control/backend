package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** C1-B Spring 외부 검색 API와 FastAPI 내부 검색 API의 공통 DTO입니다. */
public final class RagDto {
    private RagDto() {}

    public record SearchRequest(
            @NotBlank @Size(max = 2000) String query,
            @NotNull @Valid SearchFilters filters,
            @JsonProperty("top_k") @Min(1) @Max(20) Integer topK
    ) {
        public int resolvedTopK() {
            return topK == null ? 5 : topK;
        }
    }

    public record SearchFilters(
            @JsonProperty("contract_id") @Positive Long contractId,
            @JsonProperty("supplier_id") @Positive Long supplierId,
            @JsonProperty("material_id") @Positive Long materialId
    ) {}

    public record FastApiResponse(
            boolean success,
            SearchResult data,
            Instant timestamp
    ) {}

    public record SearchResult(
            List<SearchItem> results,
            boolean mock
    ) {}

    public record SearchItem(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("chunk_index") int chunkIndex,
            @JsonProperty("page_number") int pageNumber,
            String content,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("similarity_score") double similarityScore,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("mock_embedding") boolean mockEmbedding
    ) {}
}

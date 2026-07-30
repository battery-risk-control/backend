package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 아웃바운드 문서 업로드에서 사용하는 외부·내부 통신 DTO 모음입니다. {@link DocumentDto}의
 * 아웃바운드 버전 — supplier_id/material_id 대신 product_id/customer_id를 씁니다.
 */
public final class OutboundDocumentDto {
    private OutboundDocumentDto() {}

    public record UploadResponse(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("outbound_contract_id") Long outboundContractId,
            @JsonProperty("product_id") Long productId,
            @JsonProperty("customer_id") Long customerId,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            boolean duplicate,
            boolean mock,
            @JsonProperty("processed_at") Instant processedAt
    ) {}

    public record FastApiResponse(boolean success, FastApiData data, Instant timestamp) {}

    public record FastApiData(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("product_id") Long productId,
            @JsonProperty("customer_id") Long customerId,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("mock_embedding") boolean mockEmbedding,
            boolean duplicate,
            boolean mock
    ) {}
}

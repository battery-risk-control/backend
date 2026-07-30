package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** C1 문서 업로드에서 사용하는 외부·내부 통신 DTO 모음입니다. */
public final class DocumentDto {
    private DocumentDto() {}

    public record UploadResponse(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
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
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
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

    public record DocumentStatusResponse(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("original_file_name") String originalFileName,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("file_size_bytes") long fileSizeBytes,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("file_path") String filePath,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("processed_at") Instant processedAt
    ) {}

}

package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 계약서 문서. {@link Document}의 아웃바운드
 * 버전 — contract_id/supplier_id/material_id 대신 outbound_contract_id/product_id/customer_id.
 */
@Entity
@Table(name = "outbound_contract_documents")
public class OutboundDocument {
    @Id
    @Column(name = "document_id", nullable = false, length = 40)
    private String documentId;

    @Column(name = "outbound_contract_id", nullable = false)
    private Long outboundContractId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String contentHash;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "processing_status", nullable = false, length = 20)
    private String processingStatus;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "embedding_type", length = 50)
    private String embeddingType;

    @Column(name = "embedding_version", length = 50)
    private String embeddingVersion;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected OutboundDocument() {}

    public static OutboundDocument pending(
            String documentId, Long outboundContractId, Long productId, Long customerId,
            String documentType, String originalFileName, String mimeType,
            long fileSizeBytes, String contentHash, String filePath) {
        OutboundDocument document = new OutboundDocument();
        document.documentId = documentId;
        document.outboundContractId = outboundContractId;
        document.productId = productId;
        document.customerId = customerId;
        document.documentType = documentType;
        document.originalFileName = originalFileName;
        document.mimeType = mimeType;
        document.fileSizeBytes = fileSizeBytes;
        document.contentHash = contentHash;
        document.filePath = filePath;
        document.processingStatus = "PENDING";
        document.chunkCount = 0;
        document.createdAt = Instant.now();
        return document;
    }

    public void markProcessing() {
        processingStatus = "PROCESSING";
        errorCode = null;
        errorMessage = null;
    }

    /**
     * 같은 아웃바운드 계약의 기존 문서를 새 파일로 통째로 교체한다. document_id·outbound_contract_id·
     * created_at은 유지하고 원본 파일 Metadata와 처리 상태만 초기화한다 — 이 document_id로 FastAPI를
     * 다시 부르면 ChromaDB의 옛 청크가 지워지고 새 청크로 대체되므로 임베딩도 함께 교체된다.
     */
    public void replaceContent(
            String documentType, String originalFileName, String mimeType,
            long fileSizeBytes, String contentHash, String filePath,
            Long productId, Long customerId) {
        this.documentType = documentType;
        this.originalFileName = originalFileName;
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.contentHash = contentHash;
        this.filePath = filePath;
        this.productId = productId;
        this.customerId = customerId;
        this.processingStatus = "PROCESSING";
        this.chunkCount = 0;
        this.embeddingType = null;
        this.embeddingVersion = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.processedAt = null;
    }

    public void markCompleted(int chunkCount, String embeddingType, String embeddingVersion) {
        processingStatus = "COMPLETED";
        this.chunkCount = chunkCount;
        this.embeddingType = embeddingType;
        this.embeddingVersion = embeddingVersion;
        errorCode = null;
        errorMessage = null;
        processedAt = Instant.now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        processingStatus = "FAILED";
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        processedAt = Instant.now();
    }

    public String getDocumentId() { return documentId; }
    public Long getOutboundContractId() { return outboundContractId; }
    public Long getProductId() { return productId; }
    public Long getCustomerId() { return customerId; }
    public String getDocumentType() { return documentType; }
    public String getOriginalFileName() { return originalFileName; }
    public String getMimeType() { return mimeType; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public String getContentHash() { return contentHash; }
    public String getFilePath() { return filePath; }
    public String getProcessingStatus() { return processingStatus; }
    public int getChunkCount() { return chunkCount; }
    public String getEmbeddingType() { return embeddingType; }
    public String getEmbeddingVersion() { return embeddingVersion; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
}

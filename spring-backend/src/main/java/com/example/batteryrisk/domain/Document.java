package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "contract_documents")
public class Document {
    @Id
    @Column(name = "document_id", nullable = false, length = 40)
    private String documentId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

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

    protected Document() {}

    public static Document pending(
            String documentId, Long contractId, Long supplierId, Long materialId,
            String documentType, String originalFileName, String mimeType,
            long fileSizeBytes, String contentHash, String filePath) {
        Document document = new Document();
        document.documentId = documentId;
        document.contractId = contractId;
        document.supplierId = supplierId;
        document.materialId = materialId;
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
     * 같은 계약의 기존 문서를 새 파일로 통째로 교체한다. document_id·contract_id·created_at은
     * 유지하고 원본 파일 Metadata와 처리 상태만 초기화한다 — 이 document_id로 FastAPI를
     * 다시 부르면 ChromaDB의 옛 청크가 지워지고 새 청크로 대체되므로, 임베딩도 함께 교체된다.
     */
    public void replaceContent(
            String documentType, String originalFileName, String mimeType,
            long fileSizeBytes, String contentHash, String filePath,
            Long supplierId, Long materialId) {
        this.documentType = documentType;
        this.originalFileName = originalFileName;
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.contentHash = contentHash;
        this.filePath = filePath;
        this.supplierId = supplierId;
        this.materialId = materialId;
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
    public Long getContractId() { return contractId; }
    public Long getSupplierId() { return supplierId; }
    public Long getMaterialId() { return materialId; }
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

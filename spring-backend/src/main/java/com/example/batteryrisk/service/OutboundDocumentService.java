package com.example.batteryrisk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.batteryrisk.domain.OutboundDocument;
import com.example.batteryrisk.dto.OutboundDocumentDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentUploadException;
import com.example.batteryrisk.repository.OutboundDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 계약서 문서 업로드. {@link DocumentService}의
 * 아웃바운드 버전 — contract_id/supplier_id/material_id 대신 outbound_contract_id/product_id/
 * customer_id를 쓰고, {@code outbound_contract_documents} 테이블(별도, 인바운드 무관)에 저장한다.
 * FastAPI RAG 파이프라인({@code /api/v1/documents/process})은 그대로 재사용 — product_id/
 * customer_id를 optional 필드로 받도록 이미 확장돼 있다.
 */
@Service
public class OutboundDocumentService {
    private static final Logger log = LoggerFactory.getLogger(OutboundDocumentService.class);
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();
    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "txt", Set.of("text/plain"),
            "csv", Set.of("text/csv", "text/plain", "application/csv")
    );
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "CONTRACT", "PURCHASE_ORDER", "SPECIFICATION", "CERTIFICATE", "OTHER"
    );
    private static final Map<String, String> DOCUMENT_TYPE_PREFIXES = Map.of(
            "CONTRACT", "outcon",
            "PURCHASE_ORDER", "outpo",
            "SPECIFICATION", "outspc",
            "CERTIFICATE", "outcer",
            "OTHER", "outdoc"
    );

    private final RestClient fastApiRestClient;
    private final OutboundDocumentRepository documentRepository;
    private final long maxFileSize;
    private final Path uploadRoot;

    public OutboundDocumentService(
            RestClient fastApiRestClient,
            OutboundDocumentRepository documentRepository,
            @Value("${app.upload.max-file-size:52428800}") long maxFileSize,
            @Value("${app.upload.root:../uploads}") String uploadRoot) {
        this.fastApiRestClient = fastApiRestClient;
        this.documentRepository = documentRepository;
        this.maxFileSize = maxFileSize;
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
    }

    public OutboundDocumentDto.UploadResponse upload(
            MultipartFile file, Long outboundContractId, Long productId,
            Long customerId, String requestedDocumentType) {
        FileMetadata metadata = validate(file, outboundContractId, productId, customerId, requestedDocumentType);
        byte[] content = read(file);
        String contentHash = sha256(content);

        OutboundDocument existing = documentRepository
                .findByOutboundContractIdAndContentHash(outboundContractId, contentHash)
                .orElse(null);
        if (existing != null) {
            return toUploadResponse(existing, true, true);
        }

        String documentId = generateDocumentId(metadata.documentType());
        Path relativePath = Path.of("outbound-contracts", documentId, "original." + metadata.extension());
        Path storedFile = resolveStoredFile(relativePath);
        writeOriginal(storedFile, content);

        OutboundDocument document = OutboundDocument.pending(
                documentId, outboundContractId, productId, customerId, metadata.documentType(),
                metadata.fileName(), metadata.mimeType(), content.length, contentHash,
                relativePath.toString().replace('\\', '/'));
        try {
            documentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException exception) {
            cleanupStoredFile(storedFile);
            OutboundDocument duplicate = documentRepository
                    .findByOutboundContractIdAndContentHash(outboundContractId, contentHash)
                    .orElse(null);
            if (duplicate != null) {
                return toUploadResponse(duplicate, true, true);
            }
            throw new DocumentUploadException("DOCUMENT_METADATA_SAVE_FAILED", "문서 Metadata 저장에 실패했습니다.");
        } catch (RuntimeException exception) {
            cleanupStoredFile(storedFile);
            throw new DocumentUploadException("DOCUMENT_METADATA_SAVE_FAILED", "문서 Metadata 저장에 실패했습니다.");
        }

        document.markProcessing();
        documentRepository.saveAndFlush(document);

        try {
            OutboundDocumentDto.FastApiData data = processWithFastApi(document, content);
            document.markCompleted(
                    data.chunkCount(), data.embeddingType(), data.embeddingVersion());
            documentRepository.saveAndFlush(document);
            return toUploadResponse(document, data.duplicate(), data.mock());
        } catch (DocumentUploadException exception) {
            document.markFailed(exception.getCode(), exception.getMessage());
            documentRepository.saveAndFlush(document);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Unexpected error while completing outbound document {}", document.getDocumentId(), exception);
            document.markFailed("DOCUMENT_PROCESSING_UNEXPECTED", "문서 처리 결과 저장에 실패했습니다.");
            try {
                documentRepository.saveAndFlush(document);
            } catch (RuntimeException saveException) {
                log.error("Failed to persist FAILED status for outbound document {}",
                        document.getDocumentId(), saveException);
            }
            throw exception;
        }
    }

    private OutboundDocumentDto.FastApiData processWithFastApi(OutboundDocument document, byte[] content) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(document.getMimeType()));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
                .name("file").filename(document.getOriginalFileName()).build());
        parts.add("file", new HttpEntity<>(
                new NamedByteArrayResource(content, document.getOriginalFileName()), fileHeaders));
        parts.add("document_id", new HttpEntity<>(document.getDocumentId()));
        // FastAPI의 /process는 이제 contract_id를 인바운드/아웃바운드 공용 식별자로 받고,
        // product_id/customer_id를 추가 optional 필드로 받는다(supplier_id/material_id는 안 보냄).
        parts.add("contract_id", new HttpEntity<>(document.getOutboundContractId().toString()));
        parts.add("product_id", new HttpEntity<>(document.getProductId().toString()));
        parts.add("customer_id", new HttpEntity<>(document.getCustomerId().toString()));
        parts.add("document_type", new HttpEntity<>(document.getDocumentType()));
        parts.add("force_reprocess", new HttpEntity<>("false"));

        OutboundDocumentDto.FastApiResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/documents/process")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(OutboundDocumentDto.FastApiResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI outbound document processing failed: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw mapFastApiError(exception);
        } catch (Exception exception) {
            log.warn("FastAPI outbound document processing connection failed", exception);
            throw new DocumentUploadException(
                    "FASTAPI_UNAVAILABLE",
                    "FastAPI 문서 처리 서버에 연결할 수 없습니다.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new DocumentUploadException(
                    "INVALID_FASTAPI_RESPONSE", "FastAPI 응답이 올바르지 않습니다.",
                    HttpStatus.BAD_GATEWAY);
        }
        OutboundDocumentDto.FastApiData data = response.data();
        if (!document.getDocumentId().equals(data.documentId())) {
            throw new DocumentUploadException(
                    "DOCUMENT_ID_MISMATCH", "Spring과 FastAPI의 document_id가 일치하지 않습니다.",
                    HttpStatus.BAD_GATEWAY);
        }
        return data;
    }

    private DocumentUploadException mapFastApiError(RestClientResponseException exception) {
        try {
            JsonNode error = ERROR_BODY_MAPPER.readTree(exception.getResponseBodyAsString())
                    .path("error");
            String code = error.path("code").asText("").trim();
            String message = error.path("message").asText("").trim();
            if (!code.isBlank()) {
                return new DocumentUploadException(
                        code,
                        message.isBlank() ? "FastAPI 문서 처리에 실패했습니다." : message,
                        exception.getStatusCode());
            }
        } catch (Exception parseException) {
            log.debug("FastAPI error response parsing failed", parseException);
        }
        return new DocumentUploadException(
                "FASTAPI_DOCUMENT_PROCESSING_FAILED", "FastAPI 문서 처리에 실패했습니다.",
                exception.getStatusCode());
    }

    private FileMetadata validate(
            MultipartFile file, Long outboundContractId, Long productId,
            Long customerId, String requestedDocumentType) {
        if (file == null || file.isEmpty()) {
            throw new DocumentUploadException("EMPTY_FILE", "업로드 파일이 비어 있습니다.");
        }
        if (outboundContractId == null || outboundContractId <= 0
                || productId == null || productId <= 0
                || customerId == null || customerId <= 0) {
            throw new DocumentUploadException(
                    "INVALID_DOCUMENT_METADATA", "계약·제품·고객사 ID는 양수여야 합니다.");
        }
        if (!documentRepository.existsOutboundContract(outboundContractId)) {
            throw new DocumentUploadException("OUTBOUND_CONTRACT_NOT_FOUND", "아웃바운드 계약을 찾을 수 없습니다.");
        }
        if (!documentRepository.existsProduct(productId)) {
            throw new DocumentUploadException("PRODUCT_NOT_FOUND", "제품을 찾을 수 없습니다.");
        }
        if (!documentRepository.existsCustomer(customerId)) {
            throw new DocumentUploadException("CUSTOMER_NOT_FOUND", "고객사를 찾을 수 없습니다.");
        }
        if (file.getSize() > maxFileSize) {
            throw new DocumentUploadException("FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다.");
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mimeType = normalizeMimeType(file.getContentType());
        if (!ALLOWED_MIME_TYPES.containsKey(extension)) {
            throw new DocumentUploadException(
                    "UNSUPPORTED_DOCUMENT_TYPE", "PDF, TXT, CSV 파일만 업로드할 수 있습니다.");
        }
        if (!ALLOWED_MIME_TYPES.get(extension).contains(mimeType)) {
            throw new DocumentUploadException(
                    "INVALID_MIME_TYPE", "파일 확장자와 MIME 형식이 일치하지 않습니다.");
        }
        String documentType = requestedDocumentType == null
                ? "CONTRACT" : requestedDocumentType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_DOCUMENT_TYPES.contains(documentType)) {
            throw new DocumentUploadException("INVALID_DOCUMENT_TYPE", "지원하지 않는 문서 유형입니다.");
        }
        return new FileMetadata(fileName, extension, mimeType, documentType);
    }

    private String sanitizeFileName(String originalFileName) {
        String normalized = originalFileName == null ? "" : originalFileName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new DocumentUploadException("INVALID_FILE_NAME", "파일명이 올바르지 않습니다.");
        }
        return fileName;
    }

    private String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private Path resolveStoredFile(Path relativePath) {
        Path target = uploadRoot.resolve(relativePath).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new DocumentUploadException("INVALID_FILE_PATH", "파일 저장 경로가 올바르지 않습니다.");
        }
        return target;
    }

    private void writeOriginal(Path storedFile, byte[] content) {
        try {
            Files.createDirectories(storedFile.getParent());
            Files.write(storedFile, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            cleanupStoredFile(storedFile);
            throw new DocumentUploadException("FILE_STORAGE_FAILED", "원본 파일 저장에 실패했습니다.");
        }
    }

    private void cleanupStoredFile(Path storedFile) {
        try {
            Files.deleteIfExists(storedFile);
            Files.deleteIfExists(storedFile.getParent());
        } catch (IOException ignored) {
            // 원래 저장 오류를 보존하며, 남은 파일은 운영 정리 작업에서 처리합니다.
        }
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new DocumentUploadException("FILE_READ_FAILED", "파일을 읽을 수 없습니다.");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private OutboundDocumentDto.UploadResponse toUploadResponse(
            OutboundDocument document, boolean duplicate, boolean mock) {
        return new OutboundDocumentDto.UploadResponse(
                document.getDocumentId(), document.getOutboundContractId(), document.getProductId(),
                document.getCustomerId(), document.getDocumentType(), document.getOriginalFileName(),
                document.getContentHash(), document.getChunkCount(), document.getProcessingStatus(),
                document.getEmbeddingType(), document.getEmbeddingVersion(),
                duplicate, mock, document.getProcessedAt());
    }

    private String generateDocumentId(String documentType) {
        String prefix = DOCUMENT_TYPE_PREFIXES.getOrDefault(documentType, "outdoc");
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record FileMetadata(String fileName, String extension, String mimeType, String documentType) {}

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String fileName;

        private NamedByteArrayResource(byte[] content, String fileName) {
            super(content);
            this.fileName = fileName;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }
}

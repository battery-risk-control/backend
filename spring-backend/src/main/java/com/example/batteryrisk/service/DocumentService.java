package com.example.batteryrisk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.batteryrisk.domain.Document;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentNotFoundException;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentUploadException;
import com.example.batteryrisk.repository.DocumentRepository;
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

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();
    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "txt", Set.of("text/plain")
    );
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "LTA", "PURCHASE_GUIDELINE", "SUPPLIER_EVALUATION",
            "QUALITY_CERTIFICATE", "REGULATION", "TECHNICAL_SPEC"
    );

    private final RestClient fastApiRestClient;
    private final DocumentRepository documentRepository;
    private final long maxFileSize;
    private final Path uploadRoot;

    public DocumentService(
            RestClient fastApiRestClient,
            DocumentRepository documentRepository,
            @Value("${app.upload.max-file-size:10485760}") long maxFileSize,
            @Value("${app.upload.root:../uploads}") String uploadRoot) {
        this.fastApiRestClient = fastApiRestClient;
        this.documentRepository = documentRepository;
        this.maxFileSize = maxFileSize;
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
    }

    public DocumentDto.UploadResponse upload(
            MultipartFile file, Long contractId, Long supplierId,
            Long materialId, String requestedDocumentType) {
        FileMetadata metadata = validate(file, contractId, supplierId, materialId, requestedDocumentType);
        byte[] content = read(file);
        String contentHash = sha256(content);

        Document existing = documentRepository.findByContractIdAndContentHash(contractId, contentHash)
                .orElse(null);
        if (existing != null) {
            return toUploadResponse(existing, true, true);
        }

        UUID documentId = UUID.randomUUID();
        Path relativePath = Path.of("contracts", documentId.toString(), "original." + metadata.extension());
        Path storedFile = resolveStoredFile(relativePath);
        writeOriginal(storedFile, content);

        Document document = Document.pending(
                documentId, contractId, supplierId, materialId, metadata.documentType(),
                metadata.fileName(), metadata.mimeType(), content.length, contentHash,
                relativePath.toString().replace('\\', '/'));
        try {
            documentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException exception) {
            cleanupStoredFile(storedFile);
            Document duplicate = documentRepository.findByContractIdAndContentHash(contractId, contentHash)
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
            DocumentDto.FastApiData data = processWithFastApi(document, content, false);
            document.markCompleted(
                    data.chunkCount(), data.embeddingType(), data.embeddingVersion());
            documentRepository.saveAndFlush(document);
            return toUploadResponse(document, data.duplicate(), data.mock());
        } catch (DocumentUploadException exception) {
            document.markFailed(exception.getCode(), exception.getMessage());
            documentRepository.saveAndFlush(document);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Unexpected error while completing document {}", document.getDocumentId(), exception);
            document.markFailed("DOCUMENT_PROCESSING_UNEXPECTED", "문서 처리 결과 저장에 실패했습니다.");
            try {
                documentRepository.saveAndFlush(document);
            } catch (RuntimeException saveException) {
                log.error("Failed to persist FAILED status for document {}", document.getDocumentId(), saveException);
            }
            throw exception;
        }
    }

    public DocumentDto.DocumentStatusResponse get(String documentId) {
        UUID id;
        try {
            id = UUID.fromString(documentId);
        } catch (IllegalArgumentException exception) {
            throw new DocumentNotFoundException(documentId);
        }
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return toStatusResponse(document);
    }

    public DocumentDto.UploadResponse reprocess(String documentId) {
        Document document = findDocument(documentId);
        Path storedFile = resolveStoredFile(Path.of(document.getFilePath()));
        byte[] content;
        try {
            content = Files.readAllBytes(storedFile);
        } catch (IOException exception) {
            document.markFailed("ORIGINAL_FILE_NOT_FOUND", "저장된 원본 문서를 읽을 수 없습니다.");
            documentRepository.saveAndFlush(document);
            throw new DocumentUploadException(
                    "ORIGINAL_FILE_NOT_FOUND",
                    "저장된 원본 문서를 읽을 수 없습니다.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        document.markProcessing();
        documentRepository.saveAndFlush(document);
        try {
            DocumentDto.FastApiData data = processWithFastApi(document, content, true);
            document.markCompleted(
                    data.chunkCount(), data.embeddingType(), data.embeddingVersion());
            documentRepository.saveAndFlush(document);
            return toUploadResponse(document, false, data.mock());
        } catch (DocumentUploadException exception) {
            document.markFailed(exception.getCode(), exception.getMessage());
            documentRepository.saveAndFlush(document);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Unexpected error while reprocessing document {}", document.getDocumentId(), exception);
            document.markFailed("DOCUMENT_PROCESSING_UNEXPECTED", "문서 재처리에 실패했습니다.");
            try {
                documentRepository.saveAndFlush(document);
            } catch (RuntimeException saveException) {
                log.error("Failed to persist FAILED status for document {}", document.getDocumentId(), saveException);
            }
            throw exception;
        }
    }

    private Document findDocument(String documentId) {
        UUID id;
        try {
            id = UUID.fromString(documentId);
        } catch (IllegalArgumentException exception) {
            throw new DocumentNotFoundException(documentId);
        }
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private DocumentDto.FastApiData processWithFastApi(
            Document document, byte[] content, boolean forceReprocess) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(document.getMimeType()));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
                .name("file").filename(document.getOriginalFileName()).build());
        parts.add("file", new HttpEntity<>(
                new NamedByteArrayResource(content, document.getOriginalFileName()), fileHeaders));
        parts.add("document_id", new HttpEntity<>(document.getDocumentId().toString()));
        parts.add("contract_id", new HttpEntity<>(document.getContractId().toString()));
        parts.add("supplier_id", new HttpEntity<>(document.getSupplierId().toString()));
        parts.add("material_id", new HttpEntity<>(document.getMaterialId().toString()));
        parts.add("document_type", new HttpEntity<>(document.getDocumentType()));
        parts.add("force_reprocess", new HttpEntity<>(Boolean.toString(forceReprocess)));

        DocumentDto.FastApiResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/documents/process")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(DocumentDto.FastApiResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI document processing failed: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw mapFastApiError(exception);
        } catch (Exception exception) {
            log.warn("FastAPI document processing connection failed", exception);
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
        DocumentDto.FastApiData data = response.data();
        if (!document.getDocumentId().toString().equals(data.documentId())) {
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
            MultipartFile file, Long contractId, Long supplierId,
            Long materialId, String requestedDocumentType) {
        if (file == null || file.isEmpty()) {
            throw new DocumentUploadException("EMPTY_FILE", "업로드 파일이 비어 있습니다.");
        }
        if (contractId == null || contractId <= 0 || supplierId == null || supplierId <= 0
                || materialId == null || materialId <= 0) {
            throw new DocumentUploadException(
                    "INVALID_DOCUMENT_METADATA", "계약·공급사·자재 ID는 양수여야 합니다.");
        }
        if (!documentRepository.existsContractSupplier(contractId, supplierId)) {
            throw new DocumentUploadException(
                    "CONTRACT_SUPPLIER_NOT_FOUND", "계약과 공급사 조합을 찾을 수 없습니다.");
        }
        if (!documentRepository.existsMaterial(materialId)) {
            throw new DocumentUploadException("MATERIAL_NOT_FOUND", "자재를 찾을 수 없습니다.");
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
                    "UNSUPPORTED_DOCUMENT_TYPE", "PDF와 TXT 파일만 업로드할 수 있습니다.");
        }
        if (!ALLOWED_MIME_TYPES.get(extension).contains(mimeType)) {
            throw new DocumentUploadException(
                    "INVALID_MIME_TYPE", "파일 확장자와 MIME 형식이 일치하지 않습니다.");
        }
        String documentType = requestedDocumentType == null
                ? "LTA" : requestedDocumentType.trim().toUpperCase(Locale.ROOT);
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

    private DocumentDto.UploadResponse toUploadResponse(Document document, boolean duplicate, boolean mock) {
        return new DocumentDto.UploadResponse(
                document.getDocumentId().toString(), document.getContractId(), document.getSupplierId(),
                document.getMaterialId(), document.getDocumentType(), document.getOriginalFileName(),
                document.getContentHash(), document.getChunkCount(), document.getProcessingStatus(),
                document.getEmbeddingType(), document.getEmbeddingVersion(),
                duplicate, mock, document.getProcessedAt());
    }

    private DocumentDto.DocumentStatusResponse toStatusResponse(Document document) {
        return new DocumentDto.DocumentStatusResponse(
                document.getDocumentId().toString(), document.getContractId(), document.getSupplierId(),
                document.getMaterialId(), document.getDocumentType(), document.getOriginalFileName(),
                document.getMimeType(), document.getFileSizeBytes(), document.getContentHash(),
                document.getFilePath(), document.getProcessingStatus(), document.getChunkCount(),
                document.getEmbeddingType(), document.getEmbeddingVersion(), document.getErrorCode(),
                document.getErrorMessage(), document.getCreatedAt(), document.getProcessedAt());
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

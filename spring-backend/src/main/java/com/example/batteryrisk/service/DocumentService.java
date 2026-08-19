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
            "txt", Set.of("text/plain"),
            "csv", Set.of("text/csv", "text/plain", "application/csv")
    );
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "CONTRACT", "PURCHASE_ORDER", "SPECIFICATION", "CERTIFICATE", "OTHER"
    );
    private static final Map<String, String> DOCUMENT_TYPE_PREFIXES = Map.of(
            "CONTRACT", "con",
            "PURCHASE_ORDER", "po",
            "SPECIFICATION", "spc",
            "CERTIFICATE", "cer",
            "OTHER", "doc"
    );

    private final RestClient fastApiRestClient;
    private final DocumentRepository documentRepository;
    private final long maxFileSize;
    private final Path uploadRoot;

    public DocumentService(
            RestClient fastApiRestClient,
            DocumentRepository documentRepository,
            @Value("${app.upload.max-file-size:52428800}") long maxFileSize,
            @Value("${app.upload.root:../uploads}") String uploadRoot) {
        this.fastApiRestClient = fastApiRestClient;
        this.documentRepository = documentRepository;
        this.maxFileSize = maxFileSize;
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
    }

    public DocumentDto.UploadResponse upload(
            MultipartFile file, Long contractId, Long supplierId,
            Long materialId, String requestedDocumentType) {
        return upload(file, contractId, supplierId, materialId, requestedDocumentType, false);
    }

    /**
     * @param reembedDuplicate 내용이 동일한 중복이어도 ChromaDB 임베딩을 다시 채운다. 재배포마다 Chroma가
     *   비워지는 환경(ECS, 영속 볼륨 없음)에서 RAG 시드가 "중복"으로 스킵돼 벡터만 사라진 채 메타데이터는
     *   남아 검색이 0건이 되는 문제를 막는다(2026-08-19). FastAPI는 같은 document_id에 upsert하므로 멱등이다.
     *   UI 재업로드는 false로 종전처럼 멱등 no-op을 유지한다.
     */
    public DocumentDto.UploadResponse upload(
            MultipartFile file, Long contractId, Long supplierId,
            Long materialId, String requestedDocumentType, boolean reembedDuplicate) {
        FileMetadata metadata = validate(file, contractId, supplierId, materialId, requestedDocumentType);
        byte[] content = read(file);
        String contentHash = sha256(content);

        // 같은 계약에 바이트까지 동일한 파일이 이미 있으면 기본적으로 아무 것도 하지 않는다(멱등).
        Document sameContent = documentRepository.findByContractIdAndContentHash(contractId, contentHash)
                .orElse(null);
        if (sameContent != null) {
            restoreOriginalIfMissing(sameContent, content);
            if (reembedDuplicate) {
                // Chroma가 재배포로 비어 있을 수 있으므로 임베딩을 강제로 다시 채운다(RAG 복구).
                return reembedExisting(sameContent, content);
            }
            return toUploadResponse(sameContent, true, true);
        }

        // 같은 계약에 이전 문서가 있으면, 그 document_id를 재사용해 내용을 통째로 교체한다.
        // FastAPI는 같은 document_id에 대해 옛 청크를 지우고 새 청크를 upsert하므로,
        // 임베딩이 아래에 쌓이지 않고 새 것으로 대체된다.
        Document previous = documentRepository.findFirstByContractIdOrderByCreatedAtDesc(contractId)
                .orElse(null);
        if (previous != null) {
            return replaceDocument(previous, metadata, content, contentHash, supplierId, materialId);
        }

        String documentId = generateDocumentId(metadata.documentType());
        Path relativePath = Path.of("contracts", documentId, "original." + metadata.extension());
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
                restoreOriginalIfMissing(duplicate, content);
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

    /**
     * 내용이 동일한 중복 문서의 ChromaDB 임베딩을 다시 채운다. 파일·메타데이터는 그대로 두고
     * document_id도 유지한 채 force_reprocess로 FastAPI를 불러 청크를 upsert한다(멱등). 재배포로
     * Chroma가 비었을 때 RAG 시드가 벡터를 복구하는 경로다 — FastAPI의 InMemory 문서 스토어도
     * 재기동 시 비므로 force여도 전체 재임베딩이 돌아 실제로 벡터가 채워진다.
     */
    private DocumentDto.UploadResponse reembedExisting(Document existing, byte[] content) {
        existing.markProcessing();
        documentRepository.saveAndFlush(existing);
        try {
            DocumentDto.FastApiData data = processWithFastApi(existing, content, true);
            existing.markCompleted(
                    data.chunkCount(), data.embeddingType(), data.embeddingVersion());
            documentRepository.saveAndFlush(existing);
            return toUploadResponse(existing, data.duplicate(), data.mock());
        } catch (DocumentUploadException exception) {
            existing.markFailed(exception.getCode(), exception.getMessage());
            documentRepository.saveAndFlush(existing);
            throw exception;
        }
    }

    /**
     * 계약의 기존 문서를 새 파일로 교체한다. document_id를 그대로 재사용하고 원본 파일과
     * Metadata를 덮어쓴 뒤, force_reprocess로 FastAPI를 다시 불러 옛 임베딩을 새 것으로 바꾼다.
     */
    private DocumentDto.UploadResponse replaceDocument(
            Document existing, FileMetadata metadata, byte[] content,
            String contentHash, Long supplierId, Long materialId) {
        String documentId = existing.getDocumentId();
        Path relativePath = Path.of("contracts", documentId, "original." + metadata.extension());
        Path storedFile = resolveStoredFile(relativePath);
        Path previousFile = resolveStoredFile(Path.of(existing.getFilePath()));
        overwriteOriginal(storedFile, content);
        if (!storedFile.equals(previousFile)) {
            // 확장자가 바뀌어 파일명이 달라진 경우 옛 원본을 정리한다.
            deleteStoredFileQuietly(previousFile);
        }

        existing.replaceContent(
                metadata.documentType(), metadata.fileName(), metadata.mimeType(),
                content.length, contentHash,
                relativePath.toString().replace('\\', '/'), supplierId, materialId);
        documentRepository.saveAndFlush(existing);

        try {
            DocumentDto.FastApiData data = processWithFastApi(existing, content, true);
            existing.markCompleted(
                    data.chunkCount(), data.embeddingType(), data.embeddingVersion());
            documentRepository.saveAndFlush(existing);
            // 교체가 끝난 뒤 같은 계약에 남아 있던 옛 문서들을 정리한다 — 계약당 문서 1개로 수렴시키고
            // ChromaDB에 고아 임베딩이 남지 않게 한다. 정리는 best-effort라 실패해도 업로드는 성공이다.
            deleteSiblingDocuments(existing.getContractId(), existing.getDocumentId());
            return toUploadResponse(existing, data.duplicate(), data.mock());
        } catch (DocumentUploadException exception) {
            existing.markFailed(exception.getCode(), exception.getMessage());
            documentRepository.saveAndFlush(existing);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Unexpected error while replacing document {}", existing.getDocumentId(), exception);
            existing.markFailed("DOCUMENT_PROCESSING_UNEXPECTED", "문서 교체 결과 저장에 실패했습니다.");
            try {
                documentRepository.saveAndFlush(existing);
            } catch (RuntimeException saveException) {
                log.error("Failed to persist FAILED status for document {}", existing.getDocumentId(), saveException);
            }
            throw exception;
        }
    }

    /**
     * 교체로 살아남은 문서를 뺀 나머지 옛 문서들을 정리한다. 각 형제에 대해 FastAPI 임베딩 삭제 →
     * 원본 파일 삭제 → DB 행 삭제 순으로 진행하며, 어느 단계가 실패해도 다음 형제로 넘어간다.
     */
    private void deleteSiblingDocuments(Long contractId, String survivingDocumentId) {
        for (Document sibling : documentRepository.findByContractId(contractId)) {
            if (sibling.getDocumentId().equals(survivingDocumentId)) {
                continue;
            }
            deleteFastApiEmbeddings(sibling.getDocumentId());
            cleanupStoredFile(resolveStoredFile(Path.of(sibling.getFilePath())));
            try {
                documentRepository.delete(sibling);
            } catch (RuntimeException exception) {
                log.warn("Failed to delete sibling document row {}", sibling.getDocumentId(), exception);
            }
        }
    }

    private void deleteFastApiEmbeddings(String documentId) {
        try {
            fastApiRestClient.delete()
                    .uri("/api/v1/documents/{documentId}", documentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exception) {
            // 정리 실패는 업로드 결과에 영향을 주지 않는다. 남은 임베딩은 다음 교체나 운영 정리에서 처리한다.
            log.warn("Failed to delete FastAPI embeddings for sibling document {}", documentId, exception);
        }
    }

    public DocumentDto.DocumentStatusResponse get(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return toStatusResponse(document);
    }

    /**
     * 저장된 계약서 원본 파일을 바이트로 읽어 다운로드용으로 반환한다. 브리핑 근거의 document_id로
     * 원본 계약서를 내려받는 경로에서 쓴다. 파일 위치 해석·읽기는 {@link #reprocess}와 같은 패턴이다.
     */
    public DocumentDto.DownloadFile download(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        Path storedFile = resolveStoredFile(Path.of(document.getFilePath()));
        byte[] content;
        try {
            content = Files.readAllBytes(storedFile);
        } catch (IOException exception) {
            throw new DocumentUploadException(
                    "ORIGINAL_FILE_NOT_FOUND",
                    "저장된 원본 문서를 읽을 수 없습니다.",
                    HttpStatus.NOT_FOUND);
        }
        return new DocumentDto.DownloadFile(
                content, document.getOriginalFileName(), document.getMimeType());
    }

    /**
     * ECS 교체처럼 DB는 유지되고 로컬 업로드 볼륨만 비워진 경우, RAG 시드의 동일 파일로
     * 원본을 복구한다. 중복 업로드의 멱등성은 유지하면서 다운로드·재처리가 다시 가능해진다.
     */
    private void restoreOriginalIfMissing(Document document, byte[] content) {
        Path storedFile = resolveStoredFile(Path.of(document.getFilePath()));
        if (Files.isRegularFile(storedFile)) {
            return;
        }
        log.warn("Missing original restored for duplicate document {}: {}",
                document.getDocumentId(), storedFile);
        overwriteOriginal(storedFile, content);
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
        return documentRepository.findById(documentId)
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
        parts.add("document_id", new HttpEntity<>(document.getDocumentId()));
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
                    "UNSUPPORTED_DOCUMENT_TYPE", "PDF, TXT, CSV 파일만 업로드할 수 있습니다.");
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

    private void overwriteOriginal(Path storedFile, byte[] content) {
        try {
            Files.createDirectories(storedFile.getParent());
            Files.write(storedFile, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new DocumentUploadException("FILE_STORAGE_FAILED", "원본 파일 저장에 실패했습니다.");
        }
    }

    private void deleteStoredFileQuietly(Path storedFile) {
        try {
            Files.deleteIfExists(storedFile);
        } catch (IOException ignored) {
            // 옛 원본 정리는 실패해도 교체 자체엔 영향이 없다. 남은 파일은 운영 정리에서 처리한다.
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
                document.getDocumentId(), document.getContractId(), document.getSupplierId(),
                document.getMaterialId(), document.getDocumentType(), document.getOriginalFileName(),
                document.getContentHash(), document.getChunkCount(), document.getProcessingStatus(),
                document.getEmbeddingType(), document.getEmbeddingVersion(),
                duplicate, mock, document.getProcessedAt());
    }

    private DocumentDto.DocumentStatusResponse toStatusResponse(Document document) {
        return new DocumentDto.DocumentStatusResponse(
                document.getDocumentId(), document.getContractId(), document.getSupplierId(),
                document.getMaterialId(), document.getDocumentType(), document.getOriginalFileName(),
                document.getMimeType(), document.getFileSizeBytes(), document.getContentHash(),
                document.getFilePath(), document.getProcessingStatus(), document.getChunkCount(),
                document.getEmbeddingType(), document.getEmbeddingVersion(), document.getErrorCode(),
                document.getErrorMessage(), document.getCreatedAt(), document.getProcessedAt());
    }

    private String generateDocumentId(String documentType) {
        String prefix = DOCUMENT_TYPE_PREFIXES.getOrDefault(documentType, "doc");
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

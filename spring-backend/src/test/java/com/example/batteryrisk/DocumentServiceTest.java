package com.example.batteryrisk;

import com.example.batteryrisk.domain.Document;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentNotFoundException;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentUploadException;
import com.example.batteryrisk.repository.DocumentRepository;
import com.example.batteryrisk.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

class DocumentServiceTest {
    @TempDir Path tempDir;

    private DocumentRepository repository;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        when(repository.existsContractSupplier(1L, 2L)).thenReturn(true);
        when(repository.existsMaterial(3L)).thenReturn(true);
        service = new DocumentService(
                RestClient.builder().baseUrl("http://localhost:8000").build(),
                repository, 1024, tempDir.toString());
    }

    @Test
    void rejectsUnsupportedExtensionBeforeWritingFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "invalid".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "CONTRACT"));

        assertEquals("UNSUPPORTED_DOCUMENT_TYPE", exception.getCode());
    }

    @Test
    void rejectsMismatchedMimeType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.pdf", "text/plain", "not a pdf".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "CONTRACT"));

        assertEquals("INVALID_MIME_TYPE", exception.getCode());
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "CONTRACT"));
        assertEquals("EMPTY_FILE", exception.getCode());
    }

    @Test
    void rejectsOversizedFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.txt", "text/plain", new byte[1025]);

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "CONTRACT"));

        assertEquals("FILE_TOO_LARGE", exception.getCode());
    }

    @Test
    void rejectsUnknownContractSupplierPair() {
        when(repository.existsContractSupplier(99L, 98L)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", "price clause".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 99L, 98L, 3L, "LTA"));

        assertEquals("CONTRACT_SUPPLIER_NOT_FOUND", exception.getCode());
    }

    @Test
    void rejectsUnknownMaterial() {
        when(repository.existsMaterial(99L)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", "price clause".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 99L, "LTA"));

        assertEquals("MATERIAL_NOT_FOUND", exception.getCode());
    }

    @Test
    void duplicateSeedRestoresOriginalMissingAfterContainerReplacement() throws Exception {
        byte[] content = "restored price clause".getBytes();
        String documentId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("contracts", documentId, "original.txt");
        Document existing = Document.pending(
                documentId, 1L, 2L, 3L, "CONTRACT", "seed.txt", "text/plain",
                content.length, "a".repeat(64), relativePath.toString().replace('\\', '/'));
        existing.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");
        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.of(existing));

        var result = service.upload(new MockMultipartFile(
                "file", "seed.txt", "text/plain", content), 1L, 2L, 3L, "CONTRACT");

        assertTrue(result.duplicate());
        assertEquals("restored price clause", Files.readString(tempDir.resolve(relativePath)));
    }

    @Test
    void persistsOriginalAndUsesSpringDocumentId() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<Document> savedDocument = new AtomicReference<>();
        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            savedDocument.set(document);
            return document;
        });

        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(request -> withSuccess("""
                        {"success":true,"data":{"document_id":"%s","contract_id":1,
                        "supplier_id":2,"material_id":3,"document_type":"CONTRACT","file_name":"contract.txt",
                        "content_hash":"hash","chunk_count":1,"processing_status":"COMPLETED",
                        "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                        "mock_embedding":true,
                        "duplicate":false,"mock":true},"timestamp":"2026-07-21T00:00:00Z"}
                        """.formatted(savedDocument.get().getDocumentId()), APPLICATION_JSON).createResponse(request));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "../contract.txt", "text/plain", "price clause".getBytes());

        var result = linkedService.upload(file, 1L, 2L, 3L, "CONTRACT");

        assertTrue(savedDocument.get().getDocumentId().startsWith("con_"));
        assertTrue(Pattern.matches("^con_[0-9a-f]{32}$", savedDocument.get().getDocumentId()));
        assertEquals(savedDocument.get().getDocumentId(), result.documentId());
        assertEquals("COMPLETED", result.processingStatus());
        assertEquals("MOCK_TOKEN_HASH", result.embeddingType());
        assertEquals("mock-v1", result.embeddingVersion());
        assertEquals("contract.txt", result.fileName());
        assertTrue(Files.exists(tempDir.resolve("contracts")
                .resolve(result.documentId()).resolve("original.txt")));
        verify(repository, atLeast(3)).saveAndFlush(any(Document.class));
        server.verify();
    }

    @Test
    void reusesExistingDocumentIdAndReplacesContentWhenContractAlreadyHasDocument() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // 계약 1에 이미 있는 문서: 옛 원본이 저장돼 있다.
        String documentId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("contracts", documentId, "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "old clause v1");
        Document existing = Document.pending(
                documentId, 1L, 2L, 3L, "CONTRACT", "old.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        existing.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");

        // 새 파일은 내용이 달라 동일-해시 검사에는 안 걸리고, 계약의 기존 문서로는 잡힌다.
        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByContractIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(existing));
        AtomicReference<String> sentBody = new AtomicReference<>();
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(request -> {
                    sentBody.set(new String(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsBytes()));
                    return withSuccess("""
                            {"success":true,"data":{"document_id":"%s","contract_id":1,
                            "supplier_id":2,"material_id":3,"document_type":"CONTRACT","file_name":"new.txt",
                            "content_hash":"hash","chunk_count":5,"processing_status":"COMPLETED",
                            "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                            "mock_embedding":true,"duplicate":false,"mock":true},
                            "timestamp":"2026-08-11T00:00:00Z"}
                            """.formatted(documentId), APPLICATION_JSON).createResponse(request);
                });

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.txt", "text/plain", "brand new clause v2".getBytes());

        var result = linkedService.upload(file, 1L, 2L, 3L, "CONTRACT");

        // document_id는 그대로 재사용되고, 새 청크 수/파일명으로 갱신된다.
        assertEquals(documentId, result.documentId());
        assertEquals("COMPLETED", result.processingStatus());
        assertEquals(5, existing.getChunkCount());
        assertEquals("new.txt", result.fileName());
        // 옛 원본은 새 내용으로 덮어써졌다.
        assertEquals("brand new clause v2", Files.readString(original));
        // FastAPI에는 force_reprocess=true로 넘겨 옛 임베딩을 새 것으로 교체하게 한다.
        assertTrue(sentBody.get().contains("force_reprocess"));
        assertTrue(sentBody.get().contains("true"));
        server.verify();
    }

    @Test
    void deletesLeftoverSiblingDocumentsAfterReplacingSoContractKeepsOneDocument() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // 계약 1에 문서가 둘 쌓여 있는 레거시 상태: survivor(최신) + sibling(옛).
        String survivorId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path survivorPath = Path.of("contracts", survivorId, "original.txt");
        Files.createDirectories(tempDir.resolve(survivorPath).getParent());
        Files.writeString(tempDir.resolve(survivorPath), "survivor v1");
        Document survivor = Document.pending(
                survivorId, 1L, 2L, 3L, "CONTRACT", "survivor.txt", "text/plain",
                Files.size(tempDir.resolve(survivorPath)), "a".repeat(64),
                survivorPath.toString().replace('\\', '/'));
        survivor.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");

        String siblingId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path siblingPath = Path.of("contracts", siblingId, "original.txt");
        Files.createDirectories(tempDir.resolve(siblingPath).getParent());
        Files.writeString(tempDir.resolve(siblingPath), "old sibling");
        Document sibling = Document.pending(
                siblingId, 1L, 2L, 3L, "CONTRACT", "sibling.txt", "text/plain",
                Files.size(tempDir.resolve(siblingPath)), "b".repeat(64),
                siblingPath.toString().replace('\\', '/'));
        sibling.markCompleted(4, "MOCK_TOKEN_HASH", "mock-v1");

        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByContractIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(survivor));
        when(repository.findByContractId(1L)).thenReturn(List.of(survivor, sibling));
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 교체 처리(POST)가 먼저, 그 다음 형제 임베딩 삭제(DELETE) 순서로 호출된다.
        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"document_id":"%s","contract_id":1,
                        "supplier_id":2,"material_id":3,"document_type":"CONTRACT","file_name":"new.txt",
                        "content_hash":"hash","chunk_count":7,"processing_status":"COMPLETED",
                        "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                        "mock_embedding":true,"duplicate":false,"mock":true},
                        "timestamp":"2026-08-11T00:00:00Z"}
                        """.formatted(survivorId), APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8000/api/v1/documents/" + siblingId))
                .andExpect(method(DELETE))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"document_id":"%s","deleted_chunks":4}}
                        """.formatted(siblingId), APPLICATION_JSON));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.txt", "text/plain", "survivor v2 replacement".getBytes());

        var result = linkedService.upload(file, 1L, 2L, 3L, "CONTRACT");

        // survivor의 document_id는 유지, 내용은 교체.
        assertEquals(survivorId, result.documentId());
        assertEquals("survivor v2 replacement", Files.readString(tempDir.resolve(survivorPath)));
        // 형제 문서는 DB 행·원본 파일이 모두 제거되어 계약당 문서 1개로 수렴한다.
        verify(repository).delete(sibling);
        assertTrue(!Files.exists(tempDir.resolve(siblingPath)));
        server.verify();
    }

    @Test
    void preservesVectorStoreFailureCodeFromFastApi() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<Document> savedDocument = new AtomicReference<>();
        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            savedDocument.set(document);
            return document;
        });
        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR)
                        .contentType(APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"VECTOR_STORE_FAILED",
                                "message":"ChromaDB 문서 청크 저장에 실패했습니다."}}
                                """));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", "price clause".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> linkedService.upload(file, 1L, 2L, 3L, "CONTRACT"));

        assertEquals("VECTOR_STORE_FAILED", exception.getCode());
        assertEquals("FAILED", savedDocument.get().getProcessingStatus());
        assertEquals("VECTOR_STORE_FAILED", savedDocument.get().getErrorCode());
        server.verify();
    }

    @Test
    void reprocessesStoredOriginalWithSameDocumentId() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String documentId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("contracts", documentId, "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "Article 1 Price adjustment clause");
        Document document = Document.pending(
                documentId, 1L, 2L, 3L, "CONTRACT", "contract.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        document.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");
        when(repository.findById(documentId)).thenReturn(Optional.of(document));
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"document_id":"%s","contract_id":1,
                        "supplier_id":2,"material_id":3,"document_type":"CONTRACT","file_name":"contract.txt",
                        "content_hash":"hash","chunk_count":1,"processing_status":"COMPLETED",
                        "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                        "mock_embedding":true,"duplicate":false,"mock":true},
                        "timestamp":"2026-07-22T00:00:00Z"}
                        """.formatted(documentId), APPLICATION_JSON));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        var result = linkedService.reprocess(documentId);

        assertEquals(documentId, result.documentId());
        assertEquals("COMPLETED", document.getProcessingStatus());
        assertEquals(1, document.getChunkCount());
        assertTrue(!result.duplicate());
        server.verify();
    }

    @Test
    void storesFastApiUnavailableWhenConnectionFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<Document> savedDocument = new AtomicReference<>();
        when(repository.findByContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            savedDocument.set(document);
            return document;
        });
        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andRespond(withException(new IOException("offline")));
        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", "price clause".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> linkedService.upload(file, 1L, 2L, 3L, "CONTRACT"));

        assertEquals("FASTAPI_UNAVAILABLE", exception.getCode());
        assertEquals(503, exception.getStatus().value());
        assertEquals("FAILED", savedDocument.get().getProcessingStatus());
        assertEquals("FASTAPI_UNAVAILABLE", savedDocument.get().getErrorCode());
        server.verify();
    }

    @Test
    void downloadReturnsStoredOriginalBytesWithNameAndMimeType() throws Exception {
        String documentId = "con_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("contracts", documentId, "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "계약 조항 원문");
        Document document = Document.pending(
                documentId, 1L, 2L, 3L, "CONTRACT", "계약서.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        when(repository.findById(documentId)).thenReturn(Optional.of(document));

        var result = service.download(documentId);

        assertEquals("계약 조항 원문", new String(result.content(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("계약서.txt", result.fileName());
        assertEquals("text/plain", result.contentType());
    }

    @Test
    void downloadThrowsNotFoundWhenDocumentMissing() {
        when(repository.findById("con_missing")).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class, () -> service.download("con_missing"));
    }

    @Test
    void downloadThrowsWhenStoredFileGone() {
        String documentId = "con_" + UUID.randomUUID().toString().replace("-", "");
        // DB에는 경로가 있으나 실제 파일은 없다.
        Document document = Document.pending(
                documentId, 1L, 2L, 3L, "CONTRACT", "gone.txt", "text/plain",
                10, "a".repeat(64), Path.of("contracts", documentId, "original.txt").toString().replace('\\', '/'));
        when(repository.findById(documentId)).thenReturn(Optional.of(document));

        DocumentUploadException exception = assertThrows(
                DocumentUploadException.class, () -> service.download(documentId));
        assertEquals("ORIGINAL_FILE_NOT_FOUND", exception.getCode());
        assertEquals(404, exception.getStatus().value());
    }
}

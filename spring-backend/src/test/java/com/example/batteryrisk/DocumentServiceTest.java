package com.example.batteryrisk;

import com.example.batteryrisk.domain.Document;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
                () -> service.upload(file, 1L, 2L, 3L, "LTA"));

        assertEquals("UNSUPPORTED_DOCUMENT_TYPE", exception.getCode());
    }

    @Test
    void rejectsMismatchedMimeType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.pdf", "text/plain", "not a pdf".getBytes());

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "LTA"));

        assertEquals("INVALID_MIME_TYPE", exception.getCode());
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "LTA"));
        assertEquals("EMPTY_FILE", exception.getCode());
    }

    @Test
    void rejectsOversizedFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.txt", "text/plain", new byte[1025]);

        DocumentUploadException exception = assertThrows(DocumentUploadException.class,
                () -> service.upload(file, 1L, 2L, 3L, "LTA"));

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
                        "supplier_id":2,"material_id":3,"document_type":"LTA","file_name":"contract.txt",
                        "content_hash":"hash","chunk_count":1,"processing_status":"COMPLETED",
                        "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                        "mock_embedding":true,
                        "duplicate":false,"mock":true},"timestamp":"2026-07-21T00:00:00Z"}
                        """.formatted(savedDocument.get().getDocumentId()), APPLICATION_JSON).createResponse(request));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "../contract.txt", "text/plain", "price clause".getBytes());

        var result = linkedService.upload(file, 1L, 2L, 3L, "LTA");

        assertEquals(savedDocument.get().getDocumentId().toString(), result.documentId());
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
                () -> linkedService.upload(file, 1L, 2L, 3L, "LTA"));

        assertEquals("VECTOR_STORE_FAILED", exception.getCode());
        assertEquals("FAILED", savedDocument.get().getProcessingStatus());
        assertEquals("VECTOR_STORE_FAILED", savedDocument.get().getErrorCode());
        server.verify();
    }

    @Test
    void reprocessesStoredOriginalWithSameDocumentId() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID documentId = UUID.randomUUID();
        Path relativePath = Path.of("contracts", documentId.toString(), "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "Article 1 Price adjustment clause");
        Document document = Document.pending(
                documentId, 1L, 2L, 3L, "LTA", "contract.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        document.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");
        when(repository.findById(documentId)).thenReturn(Optional.of(document));
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"document_id":"%s","contract_id":1,
                        "supplier_id":2,"material_id":3,"document_type":"LTA","file_name":"contract.txt",
                        "content_hash":"hash","chunk_count":1,"processing_status":"COMPLETED",
                        "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                        "mock_embedding":true,"duplicate":false,"mock":true},
                        "timestamp":"2026-07-22T00:00:00Z"}
                        """.formatted(documentId), APPLICATION_JSON));

        DocumentService linkedService = new DocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        var result = linkedService.reprocess(documentId.toString());

        assertEquals(documentId.toString(), result.documentId());
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
                () -> linkedService.upload(file, 1L, 2L, 3L, "LTA"));

        assertEquals("FASTAPI_UNAVAILABLE", exception.getCode());
        assertEquals(503, exception.getStatus().value());
        assertEquals("FAILED", savedDocument.get().getProcessingStatus());
        assertEquals("FASTAPI_UNAVAILABLE", savedDocument.get().getErrorCode());
        server.verify();
    }
}

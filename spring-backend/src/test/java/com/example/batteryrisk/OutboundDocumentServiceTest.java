package com.example.batteryrisk;

import com.example.batteryrisk.domain.OutboundDocument;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentNotFoundException;
import com.example.batteryrisk.repository.OutboundDocumentRepository;
import com.example.batteryrisk.service.OutboundDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 아웃바운드(납품) 계약 문서의 재처리·교체가 인바운드와 대칭으로 동작하는지 검증한다. */
class OutboundDocumentServiceTest {
    @TempDir Path tempDir;

    private OutboundDocumentRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(OutboundDocumentRepository.class);
    }

    private String successBody(String documentId, int chunkCount) {
        return """
                {"success":true,"data":{"document_id":"%s","contract_id":2,
                "product_id":3,"customer_id":4,"document_type":"CONTRACT","file_name":"ctr-out.txt",
                "content_hash":"hash","chunk_count":%d,"processing_status":"COMPLETED",
                "embedding_type":"MOCK_TOKEN_HASH","embedding_version":"mock-v1",
                "mock_embedding":true,"duplicate":false,"mock":true},
                "timestamp":"2026-08-11T00:00:00Z"}
                """.formatted(documentId, chunkCount);
    }

    @Test
    void reprocessReadsStoredOriginalAndReembedsWithSameDocumentId() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String documentId = "outcon_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("outbound-contracts", documentId, "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "제3조 납기 및 지연 위약금");
        OutboundDocument document = OutboundDocument.pending(
                documentId, 2L, 3L, 4L, "CONTRACT", "ctr-out.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        document.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");

        AtomicReference<String> sentBody = new AtomicReference<>();
        when(repository.findById(documentId)).thenReturn(Optional.of(document));
        when(repository.saveAndFlush(any(OutboundDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(request -> {
                    sentBody.set(new String(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsBytes()));
                    return withSuccess(successBody(documentId, 4), APPLICATION_JSON).createResponse(request);
                });

        OutboundDocumentService service = new OutboundDocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        var result = service.reprocess(documentId);

        assertEquals(documentId, result.documentId());
        assertEquals("COMPLETED", document.getProcessingStatus());
        assertEquals(4, document.getChunkCount());
        // 재처리는 force_reprocess=true로 나가 옛 임베딩을 새 것으로 교체한다.
        assertTrue(sentBody.get().contains("force_reprocess"));
        assertTrue(sentBody.get().contains("true"));
        server.verify();
    }

    @Test
    void reprocessThrowsNotFoundWhenDocumentMissing() {
        when(repository.findById("outcon_missing")).thenReturn(Optional.empty());
        OutboundDocumentService service = new OutboundDocumentService(
                RestClient.builder().baseUrl("http://localhost:8000").build(),
                repository, 1024, tempDir.toString());
        assertThrows(DocumentNotFoundException.class, () -> service.reprocess("outcon_missing"));
    }

    @Test
    void uploadReusesExistingDocumentIdAndReplacesContent() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String documentId = "outcon_" + UUID.randomUUID().toString().replace("-", "");
        Path relativePath = Path.of("outbound-contracts", documentId, "original.txt");
        Path original = tempDir.resolve(relativePath);
        Files.createDirectories(original.getParent());
        Files.writeString(original, "old v1");
        OutboundDocument existing = OutboundDocument.pending(
                documentId, 2L, 3L, 4L, "CONTRACT", "old.txt", "text/plain",
                Files.size(original), "a".repeat(64), relativePath.toString().replace('\\', '/'));
        existing.markCompleted(2, "MOCK_TOKEN_HASH", "mock-v1");

        when(repository.existsOutboundContract(2L)).thenReturn(true);
        when(repository.existsProduct(3L)).thenReturn(true);
        when(repository.existsCustomer(4L)).thenReturn(true);
        // 새 파일은 내용이 달라 동일-해시엔 안 걸리고, 계약의 기존 문서로 잡힌다.
        when(repository.findByOutboundContractIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByOutboundContractIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.of(existing));
        when(repository.findByOutboundContractId(2L)).thenReturn(List.of(existing));
        when(repository.saveAndFlush(any(OutboundDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        server.expect(requestTo("http://localhost:8000/api/v1/documents/process"))
                .andExpect(method(POST))
                .andRespond(withSuccess(successBody(documentId, 5), APPLICATION_JSON));

        OutboundDocumentService service = new OutboundDocumentService(
                builder.build(), repository, 1024, tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.txt", "text/plain", "brand new v2".getBytes());

        var result = service.upload(file, 2L, 3L, 4L, "CONTRACT");

        // document_id는 그대로 재사용, 내용/청크 수 갱신, 원본 파일 교체.
        assertEquals(documentId, result.documentId());
        assertEquals("COMPLETED", result.processingStatus());
        assertEquals(5, existing.getChunkCount());
        assertEquals("brand new v2", Files.readString(original));
        server.verify();
    }
}

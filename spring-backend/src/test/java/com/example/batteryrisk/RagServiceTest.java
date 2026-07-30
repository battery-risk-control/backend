package com.example.batteryrisk;

import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.RagSearchException;
import com.example.batteryrisk.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RagServiceTest {
    private MockRestServiceServer server;
    private RagService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new RagService(builder.build());
    }

    @Test
    void forwardsSnakeCaseFiltersAndReturnsEvidenceChunks() {
        server.expect(requestTo("http://localhost:8000/api/v1/rag/search"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"query":"리튬 가격 조정","filters":{"contract_id":1,
                        "supplier_id":2,"material_id":3},"top_k":5}
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"results":[{
                          "document_id":"DOC-1","contract_id":1,"supplier_id":2,
                          "material_id":3,"document_type":"LTA","chunk_index":0,
                          "page_number":1,"content":"리튬 가격 조정 조항",
                          "content_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "similarity_score":0.75,"embedding_type":"MOCK_TOKEN_HASH",
                          "embedding_version":"mock-v1","mock_embedding":true
                        }],"mock":true},"timestamp":"2026-07-22T00:00:00Z"}
                        """, APPLICATION_JSON));

        RagDto.SearchResult result = service.search(new RagDto.SearchRequest(
                "리튬 가격 조정", new RagDto.SearchFilters(1L, 2L, 3L), null));

        assertEquals(1, result.results().size());
        assertEquals("DOC-1", result.results().get(0).documentId());
        assertEquals(0.75, result.results().get(0).similarityScore());
        assertEquals("MOCK_TOKEN_HASH", result.results().get(0).embeddingType());
        server.verify();
    }

    @Test
    void requiresContractOrSupplierFilter() {
        RagSearchException exception = assertThrows(RagSearchException.class,
                () -> service.search(new RagDto.SearchRequest(
                        "리튬", new RagDto.SearchFilters(null, null, 3L), 5)));

        assertEquals("RAG_FILTER_REQUIRED", exception.getCode());
        assertEquals(422, exception.getStatus().value());
    }

    @Test
    void preservesVectorStoreUnavailableFromFastApi() {
        server.expect(requestTo("http://localhost:8000/api/v1/rag/search"))
                .andRespond(withStatus(SERVICE_UNAVAILABLE)
                        .contentType(APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"VECTOR_STORE_UNAVAILABLE",
                                "message":"ChromaDB에 연결할 수 없습니다."}}
                                """));

        RagSearchException exception = assertThrows(RagSearchException.class,
                () -> service.search(new RagDto.SearchRequest(
                        "리튬", new RagDto.SearchFilters(1L, null, null), 5)));

        assertEquals("VECTOR_STORE_UNAVAILABLE", exception.getCode());
        assertEquals(503, exception.getStatus().value());
        server.verify();
    }
}

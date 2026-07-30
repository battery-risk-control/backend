package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler.RagSearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

    private final RestClient fastApiRestClient;

    public RagService(RestClient fastApiRestClient) {
        this.fastApiRestClient = fastApiRestClient;
    }

    public RagDto.SearchResult search(RagDto.SearchRequest request) {
        if (request == null || request.filters() == null) {
            throw new RagSearchException(
                    "INVALID_REQUEST", "검색 요청과 filters가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        if (request.filters().contractId() == null
                && request.filters().supplierId() == null
                && request.filters().productId() == null) {
            throw new RagSearchException(
                    "RAG_FILTER_REQUIRED",
                    "contract_id, supplier_id 또는 product_id가 필요합니다.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        RagDto.FastApiResponse response;
        try {
            RagDto.SearchRequest upstreamRequest = new RagDto.SearchRequest(
                    request.query(), request.filters(), request.resolvedTopK());
            response = fastApiRestClient.post()
                    .uri("/api/v1/rag/search")
                    .body(upstreamRequest)
                    .retrieve()
                    .body(RagDto.FastApiResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI RAG search failed: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw mapFastApiError(exception);
        } catch (Exception exception) {
            log.warn("FastAPI RAG search connection failed", exception);
            throw new RagSearchException(
                    "FASTAPI_UNAVAILABLE",
                    "FastAPI 검색 서버에 연결할 수 없습니다.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().results() == null) {
            throw new RagSearchException(
                    "INVALID_FASTAPI_RESPONSE",
                    "FastAPI 검색 응답이 올바르지 않습니다.",
                    HttpStatus.BAD_GATEWAY);
        }
        return response.data();
    }

    private RagSearchException mapFastApiError(RestClientResponseException exception) {
        try {
            JsonNode error = ERROR_BODY_MAPPER.readTree(exception.getResponseBodyAsString())
                    .path("error");
            String code = error.path("code").asText("").trim();
            String message = error.path("message").asText("").trim();
            if (!code.isBlank()) {
                return new RagSearchException(
                        code,
                        message.isBlank() ? "FastAPI 문서 검색에 실패했습니다." : message,
                        exception.getStatusCode());
            }
        } catch (Exception parseException) {
            log.debug("FastAPI RAG error response parsing failed", parseException);
        }
        return new RagSearchException(
                "FASTAPI_RAG_SEARCH_FAILED",
                "FastAPI 문서 검색에 실패했습니다.",
                exception.getStatusCode());
    }
}

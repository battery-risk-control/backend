package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ExtractionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * FastAPI의 LLM 추출기(/api/v1/internal/llm/extract)를 호출하는 공용 클라이언트입니다.
 * 실시간 분석 트리거(CollectionService)가 사용합니다.
 */
@Service
public class ExtractionClient {
    private static final Logger log = LoggerFactory.getLogger(ExtractionClient.class);

    private final RestClient fastApiRestClient;

    public ExtractionClient(RestClient fastApiRestClient) {
        this.fastApiRestClient = fastApiRestClient;
    }

    public ExtractionDto.ExtractData extract(String title, String content, String countryCode) {
        try {
            ExtractionDto.ExtractResponse response = fastApiRestClient.post()
                    .uri("/api/v1/internal/llm/extract")
                    .body(new ExtractionDto.ExtractRequest(title, content, countryCode))
                    .retrieve()
                    .body(ExtractionDto.ExtractResponse.class);
            return response != null ? response.data() : null;
        } catch (RuntimeException exception) {
            log.warn("FastAPI 추출 호출 실패: {}", exception.getMessage());
            return null;
        }
    }
}

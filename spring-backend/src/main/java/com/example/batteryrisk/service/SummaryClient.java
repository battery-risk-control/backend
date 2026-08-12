package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.SummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * FastAPI 자세한 요약 생성기(/api/v1/internal/llm/summarize)를 호출하는 클라이언트.
 * 경영기획 AI 브리핑 상세 화면이 뉴스 원문을 자세한 한국어 요약으로 바꿔 캐시할 때 쓴다.
 */
@Service
public class SummaryClient {
    private static final Logger log = LoggerFactory.getLogger(SummaryClient.class);

    private final RestClient fastApiRestClient;

    public SummaryClient(RestClient fastApiRestClient) {
        this.fastApiRestClient = fastApiRestClient;
    }

    /**
     * 자세한 한국어 요약을 생성한다. LLM이 아닌 폴백(mock)이거나 호출이 실패하면 {@code null}을
     * 반환한다 — 호출자는 null이면 저장하지 않고 기존 짧은 요약으로 폴백한다.
     */
    public String summarize(String title, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            SummaryDto.SummarizeResponse response = fastApiRestClient.post()
                    .uri("/api/v1/internal/llm/summarize")
                    .body(new SummaryDto.SummarizeRequest(title == null ? "" : title, content))
                    .retrieve()
                    .body(SummaryDto.SummarizeResponse.class);
            if (response == null || response.data() == null) {
                return null;
            }
            SummaryDto.SummarizeData data = response.data();
            // mock(=실제 LLM 요약이 아닌 폴백)이거나 빈 값이면 저장하지 않도록 null 취급한다.
            if (Boolean.TRUE.equals(data.mock()) || data.summaryKr() == null || data.summaryKr().isBlank()) {
                return null;
            }
            return data.summaryKr().trim();
        } catch (RuntimeException exception) {
            log.warn("FastAPI 요약 호출 실패: {}", exception.getMessage());
            return null;
        }
    }
}

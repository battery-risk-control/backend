package com.example.batteryrisk;

import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.service.MultiAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MultiAgentServiceTest {

    @Test
    void callsFastApiMultiAgentEndpoint() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:8000");

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        MultiAgentService service =
                new MultiAgentService(builder.build());

        server.expect(requestTo(
                        "http://localhost:8000"
                                + "/api/v1/internal/multi-agent/briefings"
                ))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
        """
        {
          "success": true,
          "data": {
            "newsId": "news-001",
            "impactDomainFinal": "logistics",
            "procurementRiskLevel": "critical",
            "procurementRiskScore": 82,
            "riskReasons": ["재고 부족"],
            "erpAssessment": {
              "erpExposureScore": 90
            },
            "erpReassessment": {},
            "contractAssessment": {
              "contractGapScore": 30
            },
            "contractFindings": [],
            "recommendedActions": ["발주 확인"],
            "briefing": "구매팀 브리핑입니다.",
            "llmUsed": false,
            "llmError": null,
            "reviewPassed": true,
            "warnings": []
          },
          "timestamp": "2026-07-28T05:00:00Z"
        }
        """,
        MediaType.APPLICATION_JSON
));

        MultiAgentDto.Request request =
                new MultiAgentDto.Request(
                        "news-001",
                        "코발트 공급 지연",
                        "기사 원문",
                        "기사 요약",
                        "logistics",
                        "logistics",
                        List.of("Cobalt"),
                        "warning",
                        70,
                        Map.of("requestId", "ERP-REQ-001"),
                        11L,
                        6L,
                        5L,
                        false
                );

        MultiAgentDto.Response response =
                service.generate(request);

        assertThat(response.newsId())
                .isEqualTo("news-001");

        assertThat(response.procurementRiskLevel())
                .isEqualTo("critical");

        assertThat(response.procurementRiskScore())
                .isEqualTo(82);

        assertThat(response.briefing())
                .isEqualTo("구매팀 브리핑입니다.");

        server.verify();
    }
}
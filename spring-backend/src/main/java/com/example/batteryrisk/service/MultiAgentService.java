package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class MultiAgentService {
    private static final Logger log =
            LoggerFactory.getLogger(MultiAgentService.class);

    private final RestClient fastApiRestClient;

    public MultiAgentService(
            RestClient fastApiRestClient
    ) {
        this.fastApiRestClient = fastApiRestClient;
    }

    public MultiAgentDto.Response generate(
            MultiAgentDto.Request request
    ) {
        MultiAgentDto.FastApiResponse fastApiResponse;

        try {
            fastApiResponse = fastApiRestClient.post()
                    .uri(
                            "/api/v1/internal"
                                    + "/multi-agent/briefings"
                    )
                    .body(request)
                    .retrieve()
                    .body(
                            MultiAgentDto
                                    .FastApiResponse.class
                    );
        } catch (RestClientResponseException exception) {
            log.warn(
                    "FastAPI multi-agent request failed: "
                            + "status={}, body={}",
                    exception.getStatusCode(),
                    exception.getResponseBodyAsString()
            );

            throw new BusinessException(
                    ErrorCode.FASTAPI_BRIEFING_UNAVAILABLE
            );
        } catch (Exception exception) {
            log.warn(
                    "FastAPI multi-agent connection failed",
                    exception
            );

            throw new BusinessException(
                    ErrorCode.FASTAPI_BRIEFING_UNAVAILABLE
            );
        }

        if (fastApiResponse == null
                || !fastApiResponse.success()
                || fastApiResponse.data() == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_BRIEFING_RESPONSE
            );
        }

        MultiAgentDto.Response response =
                fastApiResponse.data();

        validateResponse(response);

        return response;
    }

    private static void validateResponse(
            MultiAgentDto.Response response
    ) {
        if (response.newsId() == null
                || response.newsId().isBlank()
                || response.procurementRiskLevel() == null
                || response.procurementRiskScore() < 0
                || response.procurementRiskScore() > 100
                || response.briefing() == null
                || response.briefing().isBlank()) {
            throw new BusinessException(
                    ErrorCode.INVALID_BRIEFING_RESPONSE
            );
        }
    }
}
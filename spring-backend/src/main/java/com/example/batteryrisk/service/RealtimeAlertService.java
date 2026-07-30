package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.RealtimeAlertDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 뉴스·모델 연동 전 프론트엔드 계약 검증에 사용할 결정론적 Mock 응답을 제공합니다. */
@Service
public class RealtimeAlertService {
    public RealtimeAlertDto.RealtimeAlertsResponse getRealtimeAlerts() {
        RealtimeAlertDto.AiEvidence evidence = new RealtimeAlertDto.AiEvidence(
                -0.85,
                -7.2,
                15,
                1,
                185.0,
                2,
                "GOV",
                "BUS",
                14.5
        );

        RealtimeAlertDto.RealtimeAlert alert = new RealtimeAlertDto.RealtimeAlert(
                123456789L,
                "ID",
                "인도네시아",
                List.of(113.9213, -0.7893),
                List.of("Nickel"),
                new RealtimeAlertDto.NewsInfo(
                        "인도네시아 술라웨시 니켈 광산 폭우로 조업 중단",
                        "생산",
                        "인도네시아 중부 술라웨시 지역의 기록적인 폭우로 니켈 채굴 및 제련 시설 가동이 중단됨.",
                        "https://example.com/mock-news/123456789"
                ),
                new RealtimeAlertDto.RiskAssessment("High", evidence)
        );

        return new RealtimeAlertDto.RealtimeAlertsResponse(
                Instant.parse("2026-07-21T11:45:00Z"),
                List.of(alert)
        );
    }
}

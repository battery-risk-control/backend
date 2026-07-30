package com.example.batteryrisk;

import com.example.batteryrisk.controller.RealtimeAlertController;
import com.example.batteryrisk.dto.RealtimeAlertDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler;
import com.example.batteryrisk.service.RealtimeAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RealtimeAlertController.class, GlobalExceptionHandler.class})
class RealtimeAlertControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean RealtimeAlertService realtimeAlertService;

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void returnsApprovedRealtimeAlertContractInSnakeCase() throws Exception {
        RealtimeAlertDto.AiEvidence evidence = new RealtimeAlertDto.AiEvidence(
                -0.85, -7.2, 15, 1, 185.0, 2, "GOV", "BUS", 14.5);
        RealtimeAlertDto.RealtimeAlert alert = new RealtimeAlertDto.RealtimeAlert(
                123456789L,
                "ID",
                "인도네시아",
                List.of(113.9213, -0.7893),
                List.of("Nickel"),
                new RealtimeAlertDto.NewsInfo("제목", "생산", "요약", "https://example.com"),
                new RealtimeAlertDto.RiskAssessment("High", evidence));
        when(realtimeAlertService.getRealtimeAlerts()).thenReturn(
                new RealtimeAlertDto.RealtimeAlertsResponse(
                        Instant.parse("2026-07-21T11:45:00Z"), List.of(alert)));

        mockMvc.perform(get("/api/v1/map/realtime-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").value("2026-07-21T11:45:00Z"))
                .andExpect(jsonPath("$.alerts[0].event_id").value(123456789))
                .andExpect(jsonPath("$.alerts[0].country_code").value("ID"))
                .andExpect(jsonPath("$.alerts[0].country_name_kr").value("인도네시아"))
                .andExpect(jsonPath("$.alerts[0].coordinates[0]").value(113.9213))
                .andExpect(jsonPath("$.alerts[0].affected_materials[0]").value("Nickel"))
                .andExpect(jsonPath("$.alerts[0].news_info.impact_domain").value("생산"))
                .andExpect(jsonPath("$.alerts[0].news_info.summary_kr").value("요약"))
                .andExpect(jsonPath("$.alerts[0].risk_assessment.final_level").value("High"))
                .andExpect(jsonPath("$.alerts[0].risk_assessment.ai_evidence.rainfall_24h_mm")
                        .value(185.0))
                .andExpect(jsonPath("$.alerts[0].risk_assessment.ai_evidence.country_is_mining_hub")
                        .value(1))
                .andExpect(jsonPath("$.alerts[0].risk_assessment.ai_evidence.stock_volatility_20d")
                        .value(14.5))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.alerts[0].eventId").doesNotExist());
    }
}

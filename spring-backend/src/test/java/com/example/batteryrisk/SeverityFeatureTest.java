package com.example.batteryrisk;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.SeverityRepository;
import com.example.batteryrisk.service.ErpService;
import com.example.batteryrisk.service.SeverityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SeverityFeatureTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-07-22T12:00:00+09:00");

    private ErpService erpService;
    private SeverityRepository repository;
    private MockRestServiceServer server;
    private SeverityService service;

    @BeforeEach
    void setUp() {
        erpService = mock(ErpService.class);
        repository = mock(SeverityRepository.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new SeverityService(erpService, repository, builder.build());
    }

    @Test
    void combinesErpContextWithExternalSignalsAndPersistsResult() {
        when(erpService.buildContext(any())).thenReturn(erpContext());
        server.expect(once(), requestTo("http://localhost:8000/api/v1/internal/severity/score"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "inventory_days": 36,
                          "safety_stock_days": 15,
                          "expected_supply_gap_days": 0,
                          "supplier_dependency_ratio": 0.45,
                          "price_change_rate": 11.5,
                          "logistics_delay_days": 7,
                          "gdacs_alert_level": 2,
                          "feoc_status": "NO",
                          "data_quality_status": "VALID"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "severity": "WARNING",
                            "score": 62.0,
                            "reason_codes": ["HIGH_PRICE_CHANGE", "LOGISTICS_DELAY", "GDACS_RED_ALERT"],
                            "calculation_details": {
                              "forced_critical": false,
                              "component_scores": {"price_change": 10.0, "logistics_delay": 12.0, "gdacs": 40.0}
                            },
                            "rule_version": "severity-rule-v1",
                            "mock": true
                          },
                          "timestamp": "2026-07-22T03:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        SeverityDto.AssessmentResponse response = service.assess(new SeverityDto.AssessmentRequest(
                "MAT-LI-CARB", "SUP-CHL-01", AS_OF, bd("11.5"), bd("7"), 2));

        assertThat(response.severity()).isEqualTo("WARNING");
        assertThat(response.score()).isEqualByComparingTo("62.0");
        assertThat(response.ruleVersion()).isEqualTo("severity-rule-v1");
        assertThat(response.inventoryDays()).isEqualByComparingTo("36");
        assertThat(response.feocStatus()).isEqualTo("NO");
        assertThat(response.mock()).isTrue();

        ArgumentCaptor<SeverityDto.AssessmentResponse> saved =
                ArgumentCaptor.forClass(SeverityDto.AssessmentResponse.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().assessmentId()).isEqualTo(response.assessmentId());
        server.verify();
    }

    @Test
    void returnsStoredAssessment() {
        SeverityDto.AssessmentResponse stored = storedAssessment();
        when(repository.findById(stored.assessmentId())).thenReturn(Optional.of(stored));

        assertThat(service.get(stored.assessmentId())).isEqualTo(stored);
    }

    @Test
    void rejectsMissingStoredAssessment() {
        UUID assessmentId = UUID.randomUUID();
        when(repository.findById(assessmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(assessmentId))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.SEVERITY_ASSESSMENT_NOT_FOUND));
    }

    @Test
    void convertsFastApiFailureToStableSpringError() {
        when(erpService.buildContext(any())).thenReturn(erpContext());
        server.expect(requestTo("http://localhost:8000/api/v1/internal/severity/score"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.assess(new SeverityDto.AssessmentRequest(
                "MAT-LI-CARB", null, AS_OF, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.FASTAPI_SEVERITY_UNAVAILABLE));
    }

    private static ErpDto.ContextResponse erpContext() {
        return new ErpDto.ContextResponse(
                2L, "MAT-LI-CARB", "Lithium Carbonate", "KG",
                bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("36000"),
                bd("1000"), bd("15000"), BigDecimal.ZERO,
                bd("36"), bd("15"), LocalDate.parse("2026-07-30"), 8,
                BigDecimal.ZERO, bd("134900"), bd("0.45"), "CONFIRMED", "APPROVED",
                "ACTIVE", "NO", 3L, "SUP-CHL-01", 2L, "CTR-001", "VALID",
                AS_OF, "ERP_MOCK", true
        );
    }

    private static SeverityDto.AssessmentResponse storedAssessment() {
        return new SeverityDto.AssessmentResponse(
                UUID.randomUUID(), 2L, 3L, "MAT-LI-CARB", "SUP-CHL-01", AS_OF,
                bd("36"), bd("15"), BigDecimal.ZERO, bd("0.45"), bd("11.5"), bd("7"),
                2, "NO", "VALID", "WARNING", bd("62.0"),
                java.util.List.of("GDACS_RED_ALERT"), Map.of("forced_critical", false),
                "severity-rule-v1", true, OffsetDateTime.now()
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

package com.example.batteryrisk;

import com.example.batteryrisk.dto.BriefingDto;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.BriefingRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.SeverityRepository;
import com.example.batteryrisk.service.BriefingService;
import com.example.batteryrisk.service.ErpService;
import com.example.batteryrisk.service.RagService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F7 근거 계보(Phase 1) 회귀 테스트.
 *
 * <p>브리핑 자동 테스트가 없던 영역이라, 계보 링크를 넣으면서 안전망을 함께 추가한다.
 * 핵심 보장: {@code generate()}가 저장한 Severity 판정의 assessment_id가
 * 그대로 브리핑에 연결되어 저장되는가.
 */
class BriefingServiceTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-07-24T12:00:00+09:00");

    private ErpService erpService;
    private RagService ragService;
    private ErpRepository erpRepository;
    private BriefingRepository briefingRepository;
    private SeverityRepository severityRepository;
    private MockRestServiceServer server;
    private BriefingService service;

    @BeforeEach
    void setUp() {
        erpService = mock(ErpService.class);
        ragService = mock(RagService.class);
        erpRepository = mock(ErpRepository.class);
        briefingRepository = mock(BriefingRepository.class);
        severityRepository = mock(SeverityRepository.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new BriefingService(
                erpService, ragService, erpRepository,
                briefingRepository, severityRepository, builder.build());
    }

    @Test
    void linksBriefingToTheSeverityAssessmentItPersisted() {
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(ragService.search(any())).thenReturn(new RagDto.SearchResult(List.of(), true));
        when(erpRepository.findEligibleAlternativeSuppliers(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(List.of());
        expectSeverityScore();
        expectBriefingCompose();

        BriefingDto.BriefingResponse briefing = service.generate(new BriefingDto.GenerateRequest(
                "MAT-LI-CARB", "SUP-CHL-01", AS_OF, bd("11.5"), bd("7"), 2, null, 5));

        // Severity 판정이 먼저 저장되고, 그 assessment_id가 브리핑에 연결되었는지 확인한다.
        ArgumentCaptor<SeverityDto.AssessmentResponse> savedAssessment =
                ArgumentCaptor.forClass(SeverityDto.AssessmentResponse.class);
        verify(severityRepository).save(savedAssessment.capture());

        ArgumentCaptor<BriefingDto.BriefingResponse> savedBriefing =
                ArgumentCaptor.forClass(BriefingDto.BriefingResponse.class);
        verify(briefingRepository).save(savedBriefing.capture());

        assertThat(savedAssessment.getValue().assessmentId()).isNotNull();
        assertThat(savedBriefing.getValue().assessmentId())
                .as("브리핑은 방금 저장한 Severity 판정을 근거로 연결해야 한다")
                .isEqualTo(savedAssessment.getValue().assessmentId());
        assertThat(briefing.assessmentId()).isEqualTo(savedAssessment.getValue().assessmentId());
        server.verify();
    }

    @Test
    void mapsRagChunkIndexIntoContractEvidence() {
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(ragService.search(any())).thenReturn(new RagDto.SearchResult(
                List.of(new RagDto.SearchItem(
                        "CONTRACT-DOC-1", 2L, 3L, 2L, null, null, "CONTRACT",
                        7, 4, "가격 조정 조항 원문", "hash",
                        0.82, "MOCK_TOKEN_HASH", "mock-v1", true)),
                true));
        when(erpRepository.findEligibleAlternativeSuppliers(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(List.of());
        expectSeverityScore();
        expectBriefingCompose();

        service.generate(new BriefingDto.GenerateRequest(
                "MAT-LI-CARB", "SUP-CHL-01", AS_OF, bd("11.5"), bd("7"), 2, null, 5));

        ArgumentCaptor<BriefingDto.BriefingResponse> saved =
                ArgumentCaptor.forClass(BriefingDto.BriefingResponse.class);
        verify(briefingRepository).save(saved.capture());
        assertThat(saved.getValue().contractEvidence()).hasSize(1);
        assertThat(saved.getValue().contractEvidence().get(0).chunkIndex())
                .as("계약 근거는 정확한 청크 인덱스까지 담아 역추적할 수 있어야 한다")
                .isEqualTo(7);
        server.verify();
    }

    @Test
    void assemblesLineageFromBriefingAndItsAssessment() {
        UUID briefingId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        when(briefingRepository.findById(briefingId))
                .thenReturn(Optional.of(briefingWith(briefingId, assessmentId)));
        when(severityRepository.findById(assessmentId))
                .thenReturn(Optional.of(assessmentWith(assessmentId)));

        BriefingDto.LineageResponse lineage = service.getLineage(briefingId);

        assertThat(lineage.briefingId()).isEqualTo(briefingId);
        assertThat(lineage.assessment()).isNotNull();
        assertThat(lineage.assessment().assessmentId()).isEqualTo(assessmentId);
        // 계보에 ERP 입력 스냅샷이 실제로 딸려 나오는지 — 이게 "결론 → 근거" 추적의 핵심이다.
        assertThat(lineage.assessment().inventoryDays()).isEqualByComparingTo("36");
        assertThat(lineage.assessment().feocStatus()).isEqualTo("NO");
        assertThat(lineage.ruleVersion()).isEqualTo("severity-rule-v1");
    }

    @Test
    void lineageToleratesLegacyBriefingWithoutAssessment() {
        UUID briefingId = UUID.randomUUID();
        when(briefingRepository.findById(briefingId))
                .thenReturn(Optional.of(briefingWith(briefingId, null)));

        BriefingDto.LineageResponse lineage = service.getLineage(briefingId);

        assertThat(lineage.assessment()).as("링크 없는 옛 브리핑은 판정이 null이어야 한다").isNull();
        verify(severityRepository, never()).findById(any());
    }

    @Test
    void lineageRejectsUnknownBriefing() {
        UUID briefingId = UUID.randomUUID();
        when(briefingRepository.findById(briefingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLineage(briefingId))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.BRIEFING_NOT_FOUND));
    }

    private static BriefingDto.BriefingResponse briefingWith(UUID briefingId, UUID assessmentId) {
        return new BriefingDto.BriefingResponse(
                briefingId, assessmentId, 2L, 3L, "MAT-LI-CARB", "SUP-CHL-01", AS_OF,
                "WARNING", bd("62.0"), "리튬 카보네이트 공급 위험 주의",
                "요약", "재고 관점", "의존도", "공급 공백",
                "근거 부족 — 담당자 확인 필요", "승인된 대체 공급사 후보가 없습니다",
                List.of("HIGH_PRICE_CHANGE"), List.of("담당자 검토"), List.of("MOCK 분석 결과"),
                List.of(), List.of(), "briefing-template-v1", "severity-rule-v1", true,
                OffsetDateTime.now());
    }

    private static SeverityDto.AssessmentResponse assessmentWith(UUID assessmentId) {
        return new SeverityDto.AssessmentResponse(
                assessmentId, 2L, 3L, "MAT-LI-CARB", "SUP-CHL-01", AS_OF,
                bd("36"), bd("15"), BigDecimal.ZERO, bd("0.45"), bd("11.5"), bd("7"),
                2, "NO", "VALID", "WARNING", bd("62.0"),
                List.of("HIGH_PRICE_CHANGE"), Map.of("forced_critical", false),
                "severity-rule-v1", true, OffsetDateTime.now());
    }

    private void expectSeverityScore() {
        server.expect(once(), requestTo("http://localhost:8000/api/v1/internal/severity/score"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "severity": "WARNING",
                            "score": 62.0,
                            "reason_codes": ["HIGH_PRICE_CHANGE"],
                            "calculation_details": {"forced_critical": false},
                            "rule_version": "severity-rule-v1",
                            "mock": true
                          },
                          "timestamp": "2026-07-24T03:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectBriefingCompose() {
        server.expect(once(), requestTo("http://localhost:8000/api/v1/internal/briefings/compose"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "headline": "리튬 카보네이트 공급 위험 주의",
                            "risk_summary": "요약",
                            "inventory_summary": "재고 관점",
                            "supplier_dependency_summary": "의존도",
                            "supply_gap_summary": "공급 공백",
                            "contract_evidence_summary": "근거 부족 — 담당자 확인 필요",
                            "alternative_supplier_summary": "승인된 대체 공급사 후보가 없습니다",
                            "recommended_checks": ["담당자 검토"],
                            "warnings": ["MOCK 분석 결과"],
                            "template_version": "briefing-template-v1",
                            "mock": true
                          },
                          "timestamp": "2026-07-24T03:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));
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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

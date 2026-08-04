package com.example.batteryrisk.executive;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.dto.RiskEventDto;
import com.example.batteryrisk.dto.executive.ExecutiveDashboardDto;
import com.example.batteryrisk.repository.executive.ExecutiveDashboardRepository;
import com.example.batteryrisk.service.DashboardService;
import com.example.batteryrisk.service.PlanningDashboardService;
import com.example.batteryrisk.service.RiskEventService;
import com.example.batteryrisk.service.executive.ExecutiveDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutiveDashboardServiceTest {
    private DashboardService dashboardService;
    private PlanningDashboardService planningDashboardService;
    private RiskEventService riskEventService;
    private ExecutiveDashboardRepository repository;

    private ExecutiveDashboardService service;

    @BeforeEach
    void setUp() {
        dashboardService =
                mock(DashboardService.class);

        planningDashboardService =
                mock(PlanningDashboardService.class);

        riskEventService =
                mock(RiskEventService.class);

        repository =
                mock(ExecutiveDashboardRepository.class);

        service = new ExecutiveDashboardService(
                dashboardService,
                planningDashboardService,
                riskEventService,
                repository
        );
    }

    @Test
    void dashboardCombinesExecutiveData() {
        OffsetDateTime assessedAt =
                OffsetDateTime.parse(
                        "2026-08-03T10:00:00+09:00"
                );

        when(
                dashboardService
                        .procurementRiskSummary()
        ).thenReturn(
                new DashboardDto.ProcurementRiskSummary(
                        8,
                        3,
                        5,
                        0,
                        new BigDecimal("75.0"),
                        new BigDecimal("60.0"),
                        8,
                        assessedAt,
                        1,
                        2,
                        new BigDecimal("70.0"),
                        new BigDecimal("65.0"),
                        false
                )
        );

        PlanningDashboardDto.MaterialRiskRankItem cobalt =
                new PlanningDashboardDto.MaterialRiskRankItem(
                        "Cobalt Sulfate",
                        new BigDecimal("80.0"),
                        1,
                        "심각"
                );

        PlanningDashboardDto.MaterialRiskRankItem lithium =
                new PlanningDashboardDto.MaterialRiskRankItem(
                        "Lithium Carbonate",
                        new BigDecimal("60.0"),
                        2,
                        "주의"
                );

        when(
                planningDashboardService.materialRisk()
        ).thenReturn(
                new PlanningDashboardDto.MaterialRiskDashboard(
                        List.of(),
                        List.of(cobalt, lithium),
                        List.of(),
                        "지난 분기 대비 위험 점수 상승"
                )
        );

        PlanningDashboardDto.CountryDependencyItem country =
                new PlanningDashboardDto.CountryDependencyItem(
                        "CD",
                        new BigDecimal("84.0")
                );

        PlanningDashboardDto.EntityBadgeItem
                alternativeSupplier =
                new PlanningDashboardDto.EntityBadgeItem(
                        "10",
                        "Han River Battery Materials",
                        "전환 및 의존도 개선 후보",
                        new PlanningDashboardDto.Badge(
                                "APPROVED",
                                "success"
                        )
                );

        when(
                planningDashboardService.importDependency()
        ).thenReturn(
                new PlanningDashboardDto.ImportDependencyDashboard(
                        List.of(),
                        List.of(country),
                        List.of(),
                        List.of(alternativeSupplier)
                )
        );

        PlanningDashboardDto.SupplierRiskRankItem supplier =
                new PlanningDashboardDto.SupplierRiskRankItem(
                        "SUP-COD-01",
                        "Cobalt Supplier",
                        3,
                        "APPROVED",
                        List.of("배터리사업부")
                );

        when(
                planningDashboardService.supplierAnalysis()
        ).thenReturn(
                new PlanningDashboardDto.SupplierAnalysisDashboard(
                        List.of(),
                        List.of(supplier),
                        List.of()
                )
        );

        PlanningDashboardDto.BriefingSummaryItem briefing =
                new PlanningDashboardDto.BriefingSummaryItem(
                        "news-001",
                        "Cobalt Sulfate",
                        "심각",
                        "코발트 공급 지연",
                        "배터리사업부"
                );

        when(
                planningDashboardService.aiBriefing()
        ).thenReturn(
                new PlanningDashboardDto
                        .AiBriefingSummaryDashboard(
                        List.of(),
                        List.of(),
                        List.of(briefing)
                )
        );

        // RiskBoardItem은 상류(#15 시점) 8개 → 이 저장소 17개 컴포넌트로 늘었다
        // (sourceUrl·collectedAt·eventId·analysisId·외부신호/종합 판정 등).
        // 이 테스트는 riskBoard() 결과를 그대로 통과시키는지만 보므로 추가 인자는 비워 둔다.
        RiskEventDto.RiskBoardItem mapItem =
                new RiskEventDto.RiskBoardItem(
                        "news-001",
                        "Cobalt Sulfate",
                        "심각",
                        "확정",
                        "코발트 공급 지연",
                        "CD",
                        "DR Congo",
                        new RiskEventDto.Coordinates(
                                -4.0,
                                21.0
                        ),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null
                );

        when(
                riskEventService.riskBoard()
        ).thenReturn(
                List.of(mapItem)
        );

        ExecutiveDashboardDto.RiskTrendPoint trend =
                new ExecutiveDashboardDto.RiskTrendPoint(
                        LocalDate.parse("2026-08-03"),
                        new BigDecimal("70.0"),
                        1,
                        1
                );

        when(
                repository.loadRiskTrend()
        ).thenReturn(
                List.of(trend)
        );

        ExecutiveDashboardDto.VerificationSummary
                verification =
                new ExecutiveDashboardDto.VerificationSummary(
                        10,
                        8,
                        2,
                        1,
                        1,
                        0
                );

        when(
                repository.loadVerificationSummary()
        ).thenReturn(
                verification
        );

        ExecutiveDashboardDto.Dashboard result =
                service.dashboard();

        assertThat(
                result.kpi().criticalCount()
        ).isEqualTo(3);

        assertThat(
                result.kpi().warningCount()
        ).isEqualTo(5);

        assertThat(
                result.kpi().averageRiskScore()
        ).isEqualByComparingTo("70.0");

        assertThat(
                result.riskMap()
        ).containsExactly(mapItem);

        assertThat(
                result.topRisks()
        ).containsExactly(
                cobalt,
                lithium
        );

        assertThat(
                result.riskTrend()
        ).containsExactly(trend);

        assertThat(
                result.countryDependency()
        ).containsExactly(country);

        assertThat(
                result.supplierRisks()
        ).containsExactly(supplier);

        assertThat(
                result.alternativeSuppliers()
        ).containsExactly(alternativeSupplier);

        assertThat(
                result.recentBriefings()
        ).containsExactly(briefing);

        assertThat(
                result.verificationSummary()
        ).isSameAs(verification);
    }
}
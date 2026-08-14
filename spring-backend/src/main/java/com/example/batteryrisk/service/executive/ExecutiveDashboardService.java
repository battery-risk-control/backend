package com.example.batteryrisk.service.executive;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.dto.executive.ExecutiveDashboardDto;
import com.example.batteryrisk.repository.executive.ExecutiveDashboardRepository;
import com.example.batteryrisk.service.DashboardService;
import com.example.batteryrisk.service.PlanningDashboardService;
import com.example.batteryrisk.service.RiskEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 3계층 경영진 대시보드 데이터를 조합한다.
 *
 * 기존 구매팀·경영기획팀 서비스를 재사용하고,
 * 위험 추세와 검증 요약은 경영진 전용 Repository에서 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class ExecutiveDashboardService {
    private static final int TOP_RISK_LIMIT = 5;
    private static final int SUPPLIER_LIMIT = 5;
    private static final int BRIEFING_LIMIT = 5;

    private final DashboardService dashboardService;
    private final PlanningDashboardService planningDashboardService;
    private final RiskEventService riskEventService;
    private final ExecutiveDashboardRepository repository;

    public ExecutiveDashboardService(
            DashboardService dashboardService,
            PlanningDashboardService planningDashboardService,
            RiskEventService riskEventService,
            ExecutiveDashboardRepository repository
    ) {
        this.dashboardService = dashboardService;
        this.planningDashboardService =
                planningDashboardService;
        this.riskEventService = riskEventService;
        this.repository = repository;
    }

    public ExecutiveDashboardDto.Dashboard dashboard() {
        DashboardDto.ProcurementRiskSummary riskSummary =
                dashboardService.procurementRiskSummary();

        PlanningDashboardDto.MaterialRiskDashboard materialRisk =
                planningDashboardService.materialRisk();

        PlanningDashboardDto.ImportDependencyDashboard importDependency =
                planningDashboardService.importDependency();

        PlanningDashboardDto.SupplierAnalysisDashboard supplierAnalysis =
                planningDashboardService.supplierAnalysis();

        PlanningDashboardDto.AiBriefingSummaryDashboard aiBriefing =
                planningDashboardService.aiBriefing(0, BRIEFING_LIMIT);

        ExecutiveDashboardDto.VerificationSummary verificationSummary =
                repository.loadVerificationSummary();

        ExecutiveDashboardDto.Recent24hSummary recent24h =
                repository.loadRecent24hSummary();

        ExecutiveDashboardDto.ExecutiveKpi kpi =
                new ExecutiveDashboardDto.ExecutiveKpi(
                        riskSummary.criticalCount(),
                        riskSummary.warningCount(),
                        averageRiskScore(materialRisk),
                        verificationSummary.passedCount(),
                        verificationSummary.reviewRequiredCount(),
                        riskSummary.latestAssessedAt(),
                        recent24h.criticalCount(),
                        recent24h.warningCount(),
                        recent24h.verifiedBriefingCount(),
                        recent24h.reviewRequiredCount(),
                        recent24h.maxRiskScore()
                );

        return new ExecutiveDashboardDto.Dashboard(
                kpi,
                riskEventService.riskBoard(),
                materialRisk.ranking()
                        .stream()
                        .limit(TOP_RISK_LIMIT)
                        .toList(),
                repository.loadRiskTrend(),
                importDependency.byCountry(),
                supplierAnalysis.ranking()
                        .stream()
                        .limit(SUPPLIER_LIMIT)
                        .toList(),
                importDependency.alternativeSuppliers()
                        .stream()
                        .limit(SUPPLIER_LIMIT)
                        .toList(),
                aiBriefing.recent()
                        .stream()
                        .limit(BRIEFING_LIMIT)
                        .toList(),
                verificationSummary,
                // 기준 시각 = 최신 공급망 뉴스 수집 시각(실시간 뉴스 유입). 경영기획 대시보드와 같은 소스로 두 화면을 일치시킨다.
                riskEventService.latestSupplyChainNewsCollectedAt()
        );
    }

    private static BigDecimal averageRiskScore(
            PlanningDashboardDto.MaterialRiskDashboard dashboard
    ) {
        BigDecimal total = dashboard.ranking()
                .stream()
                .map(
                        PlanningDashboardDto
                                .MaterialRiskRankItem
                                ::score
                )
                .filter(Objects::nonNull)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        long count = dashboard.ranking()
                .stream()
                .map(
                        PlanningDashboardDto
                                .MaterialRiskRankItem
                                ::score
                )
                .filter(Objects::nonNull)
                .count();

        if (count == 0) {
            return BigDecimal.ZERO;
        }

        return total.divide(
                BigDecimal.valueOf(count),
                1,
                RoundingMode.HALF_UP
        );
    }
}

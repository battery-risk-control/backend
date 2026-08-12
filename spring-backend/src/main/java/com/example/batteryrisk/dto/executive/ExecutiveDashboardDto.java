package com.example.batteryrisk.dto.executive;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.dto.RiskEventDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 3계층 경영진 대시보드 조회 응답 DTO. */
public final class ExecutiveDashboardDto {
    private ExecutiveDashboardDto() {}

    /** 경영진 화면 상단에 표시하는 핵심 지표. */
    public record ExecutiveKpi(
            long criticalCount,
            long warningCount,
            BigDecimal averageRiskScore,
            long verifiedBriefingCount,
            long reviewRequiredCount,
            OffsetDateTime latestAssessedAt
    ) {}

    /** 최근 30일 멀티에이전트 구매 위험 점수의 일별 추세. */
    public record RiskTrendPoint(
            LocalDate date,
            BigDecimal averageRiskScore,
            long criticalCount,
            long warningCount
    ) {}

    /** Verification Node 결과와 저장된 근거의 완전성을 요약한다. */
    public record VerificationSummary(
            long totalCount,
            long passedCount,
            long reviewRequiredCount,
            long erpEvidenceMissingCount,
            long contractEvidenceMissingCount,
            long llmWarningCount
    ) {}

    /** 경영진 첫 화면을 한 번의 API 호출로 구성하는 통합 응답. */
    public record Dashboard(
            ExecutiveKpi kpi,
            List<RiskEventDto.RiskBoardItem> riskMap,
            List<PlanningDashboardDto.MaterialRiskRankItem> topRisks,
            List<RiskTrendPoint> riskTrend,
            List<PlanningDashboardDto.CountryDependencyItem> countryDependency,
            List<PlanningDashboardDto.SupplierRiskRankItem> supplierRisks,
            List<PlanningDashboardDto.EntityBadgeItem> alternativeSuppliers,
            List<PlanningDashboardDto.BriefingSummaryItem> recentBriefings,
            VerificationSummary verificationSummary,
            OffsetDateTime asOf
    ) {}
}

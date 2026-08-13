package com.example.batteryrisk.dto.executive;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.dto.RiskEventDto;
import com.fasterxml.jackson.annotation.JsonProperty;

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
            OffsetDateTime latestAssessedAt,
            // 최근 24시간에 새로 수집·평가된 구매 리스크 건수. 1계층 KPI의 24h 보조 줄과 같은 취지로
            // 심각·주의·검증완료·검토필요 카드 하단에 "24h N건"으로 노출한다.
            //
            // @JsonProperty 명시 필요: 전역 SNAKE_CASE 전략이 문자→숫자 경계(...Count|24h)엔
            // 언더스코어를 안 넣어 "collected_count24h"로 잘못 직렬화된다(DashboardDto의 24h 필드와 동일 이유).
            @JsonProperty("collected_count_24h") long collectedCount24h,
            // 최근 24시간 구매 리스크 최고 점수(최종 합성 점수). 평가가 0건이면 null → 화면은 "—".
            @JsonProperty("max_risk_score_24h") BigDecimal maxRiskScore24h
    ) {}

    /** 최근 24시간 수집 건수·최고 위험 점수 집계(경영진 KPI 24h 보조 줄용). */
    public record Recent24hSummary(
            long collectedCount,
            BigDecimal maxRiskScore
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

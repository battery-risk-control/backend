package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 2계층(경영기획팀, 코드상 Role.STRATEGY) 대시보드 7탭 DTO를 한 파일에 모은다
 * ({@link DashboardDto}와 동일한 "폴더마다 기능 파일 최대 1개" 관례). 필드명은
 * 프론트엔드 {@code frontend/src/api/types.ts}의 기존 TypeScript 인터페이스와 1:1로
 * 맞췄다 — 전역 {@code SNAKE_CASE} Jackson 네이밍 전략에 맡기되, 숫자-문자 경계
 * (예: {@code risk_count_90d})는 전략이 잘못 변환하므로({@code DashboardDto}의
 * {@code critical_count_24h}와 동일한 이유) {@code @JsonProperty}로 명시한다.
 */
public final class PlanningDashboardDto {
    private PlanningDashboardDto() {}

    public record KpiSummaryItem(String label, BigDecimal value, String unit) {}

    /** risk_event에는 없던 사업부 개념 — 이제 실제 material_category→business_unit 매핑(V27) 기반. */
    public record RiskExposureByUnit(String businessUnit, BigDecimal exposureScore) {}

    public record VendorRiskHistoryItem(
            String vendorId,
            String vendorName,
            @JsonProperty("risk_count_90d") long riskCount90d,
            String latestGrade,
            String confidenceLabel
    ) {}

    /** 전략 대시보드 탭. */
    public record StrategyDashboard(
            String businessUnit,
            String period,
            List<KpiSummaryItem> kpiSummary,
            List<RiskExposureByUnit> riskExposureByUnit,
            List<VendorRiskHistoryItem> vendorRiskHistory
    ) {}

    /** RankedBarChart 공용 입력 항목(프론트 RankedBarItem과 1:1). */
    public record RankedBarItem(String name, BigDecimal value, String valueSuffix, String tone) {}

    public record Badge(String label, String tone) {}

    /** EntityBadgeList 공용 입력 항목(프론트 EntityBadgeItem과 1:1). */
    public record EntityBadgeItem(String id, String primary, String secondary, Badge badge) {}

    public record MaterialRiskRankItem(String material, BigDecimal score, int rank, String grade) {}

    /** 자재 위험 탭. */
    public record MaterialRiskDashboard(
            List<KpiSummaryItem> kpiSummary,
            List<MaterialRiskRankItem> ranking,
            List<RiskExposureByUnit> topMaterialUnitExposure,
            String quarterChangeLabel
    ) {}

    public record CountryDependencyItem(String country, BigDecimal shareRatio) {}

    public record UnitDependencyItem(String businessUnit, String country, BigDecimal shareRatio) {}

    /** 수입 의존도 탭. */
    public record ImportDependencyDashboard(
            List<KpiSummaryItem> kpiSummary,
            List<CountryDependencyItem> byCountry,
            List<UnitDependencyItem> byUnit,
            List<EntityBadgeItem> alternativeSuppliers
    ) {}

    public record SupplierRiskRankItem(
            String vendorId,
            String vendorName,
            @JsonProperty("risk_count_90d") long riskCount90d,
            String approvedStatus,
            List<String> linkedUnits
    ) {}

    /** 공급사 분석 탭. */
    public record SupplierAnalysisDashboard(
            List<KpiSummaryItem> kpiSummary,
            List<SupplierRiskRankItem> ranking,
            List<EntityBadgeItem> recommended
    ) {}

    public record ContractCoverageItem(String businessUnit, long contractCount) {}

    /** 계약 현황 탭. */
    public record ContractStatusDashboard(
            List<KpiSummaryItem> kpiSummary,
            List<ContractCoverageItem> coverageByUnit,
            List<EntityBadgeItem> expiring
    ) {}

    /**
     * AI 브리핑 탭 최근 목록 1건. {@code riskEventId}는 프론트 mock의 "RISK-YYYY-MMDD-NNN"
     * 패턴이 아니라 실제 {@code analyses.analysis_id}(UUID) 문자열이다 — 프론트가 이 값을
     * 파싱해 날짜를 뽑는 로직(parseRiskEventDate)이 있다면 실 연동 시 같이 손봐야 한다
     * (건의사항 3번, 공급사 ID 포맷 불일치와 같은 종류의 문제).
     */
    public record BriefingSummaryItem(
            String riskEventId, String material, String grade, String headline, String businessUnit
    ) {}

    /** AI 브리핑 탭. {@code recentTotalCount}는 {@code recent}의 전체 건수(페이지네이션용) — {@code recent} 자체는 요청한 페이지 분량만 담는다. */
    public record AiBriefingSummaryDashboard(
            List<KpiSummaryItem> kpiSummary,
            List<RankedBarItem> byUnit,
            List<BriefingSummaryItem> recent,
            long recentTotalCount
    ) {}

    public record ConfidenceDistributionItem(String label, BigDecimal ratio) {}

    /** 데이터 품질 탭. */
    public record DataQualityStatus(
            String erpSyncStatus,
            String ragIndexStatus,
            long materialCoverageCount,
            long materialCoverageTotal,
            String lastUpdatedLabel,
            List<ConfidenceDistributionItem> confidenceDistribution
    ) {}

    /**
     * AI 브리핑 상세(드릴다운). {@code analysisId}로 조회하며, 해당 분석이 아직
     * 멀티에이전트 평가(procurement_risk_assessments)를 못 받았으면 {@code briefing}
     * 이하 필드는 전부 null/빈 값이다(analyses 단독 정보만 채워짐).
     */
    public record AiBriefingDetail(
            String analysisId,
            String material,
            String businessUnit,
            String grade,
            String headline,
            String eventContent,
            String briefing,
            List<String> recommendedActions,
            List<Map<String, Object>> contractFindings,
            List<String> warnings,
            OffsetDateTime assessedAt
    ) {}

    public record ContractDocumentItem(
            String documentId, String originalFileName, String processingStatus, int chunkCount
    ) {}

    /** 계약 상세(드릴다운). {@code contractNumber}로 조회. */
    public record ContractDetail(
            String contractNumber,
            String contractName,
            String supplierName,
            String materialName,
            String businessUnit,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            List<ContractDocumentItem> documents
    ) {}
}

package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 14단계 조회·집계 API DTO를 한 파일에 모읍니다. */
public final class DashboardDto {
    private DashboardDto() {}

    /**
     * 대시보드 상단 요약.
     *
     * <p>등급별 건수는 <b>자재별 최신 Severity 1건</b>을 기준으로 집계한다.
     * 같은 자재를 여러 번 분석해도 현재 상태는 하나이므로, 누적 이력이 아니라 현재 상태를 센다.
     */
    public record Summary(
            long assessedMaterialCount,
            long criticalCount,
            long warningCount,
            long normalCount,
            long unknownCount,
            long briefingCount,
            long materialCount,
            long supplierCount,
            long contractCount,
            long documentCount,
            OffsetDateTime latestAssessedAt,
            boolean mock
    ) {}

    /**
     * 구매 리스크 KPI 요약(멀티에이전트).
     *
     * <p>자재 대분류(8종) 단위로 최신 {@code procurement_risk_assessments} 1건만 남겨 집계한다
     * ({@link com.example.batteryrisk.repository.DashboardRepository}의 {@code material_category}
     * 기준 CTE). {@link Summary}가 {@code severity_assessments} 기반인 것과 달리 이건 Chain B
     * 멀티에이전트 결과 기반이라 별도 record로 둔다.
     */
    public record ProcurementRiskSummary(
            long assessedCategoryCount,
            long criticalCount,
            long warningCount,
            long normalCount,
            BigDecimal erpExposureScoreAvg,
            BigDecimal externalSignalScoreAvg,
            long verifiedBriefingCount,
            OffsetDateTime latestAssessedAt,

            // 최근 24시간 raw 활동량 — "카테고리별 최신 1건" 스냅샷과는 별개 모집단(원본 행
            // 전체, 완료 처리 여부 무관)이라 위 필드들과 섞어 계산하지 않는다.
            //
            // @JsonProperty 명시 필요: 전역 SNAKE_CASE 전략이 문자→숫자 경계(...Count|24h)엔
            // 언더스코어를 안 넣어 "critical_count24h"로 잘못 직렬화됨(Docker 실측 중 발견) —
            // 이 파일의 다른 필드는 전략에 맡기지만 이 4개만 명시로 강제한다.
            @JsonProperty("critical_count_24h") long criticalCount24h,
            @JsonProperty("warning_count_24h") long warningCount24h,
            @JsonProperty("erp_exposure_score_avg_24h") BigDecimal erpExposureScoreAvg24h,
            @JsonProperty("external_signal_score_avg_24h") BigDecimal externalSignalScoreAvg24h,

            boolean mock
    ) {}

    /** 자재별 현재 리스크 — 프론트엔드 리스크 게이지·스코어 카드용. */
    public record MaterialRiskItem(
            String erpMaterialId,
            String materialName,
            String severity,
            BigDecimal score,
            BigDecimal inventoryDays,
            BigDecimal safetyStockDays,
            BigDecimal supplierDependencyRatio,
            String feocStatus,
            String dataQualityStatus,
            OffsetDateTime assessedAt
    ) {}

    /** 특정 자재의 공급사 점유율 분해 — 수입 의존도 도넛 차트용. 색상은 화면에서 정한다. */
    public record ImportDependencyItem(
            String erpSupplierId,
            String supplierName,
            String countryCode,
            BigDecimal supplyShareRatio,
            String approvedStatus,
            boolean isAlternative
    ) {}

    public record ImportDependency(
            String erpMaterialId,
            String materialName,
            BigDecimal totalShareRatio,
            List<ImportDependencyItem> breakdown
    ) {}

    /** 계약 목록 항목. */
    public record ContractItem(
            Long contractId,
            String erpContractId,
            String contractNumber,
            String contractName,
            String erpSupplierId,
            String supplierName,
            String erpMaterialId,
            String materialName,
            String status,
            String contractRole,
            LocalDate startDate,
            LocalDate endDate
    ) {}
}

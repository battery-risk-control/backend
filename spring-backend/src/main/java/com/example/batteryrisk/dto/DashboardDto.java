package com.example.batteryrisk.dto;

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

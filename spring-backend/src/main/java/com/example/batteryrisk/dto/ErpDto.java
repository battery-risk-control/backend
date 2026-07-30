package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** F1 ERP Context 요청·응답을 한 파일에 모은 DTO입니다. */
public final class ErpDto {
    private ErpDto() {}

    public record ContextRequest(
            @NotBlank
            @Schema(example = "MAT-LI-CARB", description = "외부 ERP 자재 ID")
            String erpMaterialId,

            @Schema(example = "SUP-CHL-01", description = "영향 공급사 ERP ID. 없으면 우선순위 1 공급사를 사용")
            String erpSupplierId,

            @NotNull
            @Schema(example = "2026-07-22T12:00:00+09:00", description = "ERP 분석 기준 시각")
            OffsetDateTime asOf
    ) {}

    public record ContextResponse(
            Long materialId,
            String erpMaterialId,
            String materialName,
            String unit,
            BigDecimal onHandQuantity,
            BigDecimal reservedQuantity,
            BigDecimal blockedQuantity,
            BigDecimal qualityHoldQuantity,
            BigDecimal availableQuantity,
            BigDecimal averageDailyUsage,
            BigDecimal safetyStockQuantity,
            BigDecimal safetyStockShortageQuantity,
            BigDecimal inventoryDays,
            BigDecimal safetyStockDays,
            LocalDate nextInboundDate,
            Integer nextEtaDays,
            BigDecimal expectedSupplyGapDays,
            BigDecimal remainingQuantity,
            BigDecimal supplierDependencyRatio,
            String purchaseOrderStatus,
            String alternativeSupplierStatus,
            String supplierStatus,
            String feocStatus,
            Long primarySupplierId,
            String erpSupplierId,
            Long primaryContractId,
            String erpContractId,
            String dataQualityStatus,
            OffsetDateTime asOf,
            String dataSource,
            boolean mock
    ) {}
}

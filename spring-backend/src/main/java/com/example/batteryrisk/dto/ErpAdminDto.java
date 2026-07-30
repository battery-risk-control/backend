package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** ERP 10개 엔티티의 단건 Upsert 요청·응답 DTO입니다. 외부 ERP ID로 자동 판별합니다. */
public final class ErpAdminDto {
    private ErpAdminDto() {}

    /** 모든 Upsert가 공유하는 응답. created=true면 신규 삽입, false면 기존 갱신입니다. */
    public record UpsertResponse(
            String entity,
            String erpId,
            Long internalId,
            boolean created,
            OffsetDateTime processedAt
    ) {}

    public record MaterialUpsertRequest(
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank String materialCode,
            @NotBlank String materialName,
            @NotBlank String materialCategory,
            @NotBlank String baseUnit,
            @NotBlank String criticality,
            boolean active,
            @NotBlank String erpGroupCode
    ) {}

    public record SupplierUpsertRequest(
            @NotBlank @Schema(example = "SUP-CHL-01") String erpSupplierId,
            @NotBlank String supplierCode,
            @NotBlank String supplierName,
            @NotBlank String countryCode,
            @NotBlank String supplierStatus,
            @NotBlank String riskLevel,
            boolean feocStatus,
            @NotBlank String certifications
    ) {}

    public record WarehouseUpsertRequest(
            @NotBlank @Schema(example = "WH-ICN-01") String erpWarehouseId,
            @NotBlank String warehouseCode,
            @NotBlank String warehouseName,
            @NotBlank String countryCode,
            @NotBlank String timezone,
            @NotBlank String warehouseType,
            boolean active
    ) {}

    public record ContractUpsertRequest(
            @NotBlank @Schema(example = "CTR-001") String erpContractId,
            @NotBlank String contractNumber,
            @NotBlank @Schema(example = "SUP-CHL-01") String erpSupplierId,
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank String contractName,
            @NotBlank String contractStatus,
            @NotNull LocalDate effectiveDate,
            LocalDate expirationDate,
            @NotBlank String documentId,
            @NotBlank String documentSource,
            @NotBlank String documentPath,
            @NotBlank String contractRole,
            @NotBlank String supplierApprovalStatus,
            @NotNull OffsetDateTime indexedAt
    ) {}

    public record SupplierMaterialUpsertRequest(
            @NotBlank @Schema(example = "SM-001") String erpSupplierMaterialId,
            @NotBlank @Schema(example = "SUP-CHL-01") String erpSupplierId,
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank @Schema(example = "CTR-001") String erpContractId,
            @NotNull @DecimalMin("0") BigDecimal supplyShareRatio,
            @NotNull @Positive Integer leadTimeDays,
            @NotNull @DecimalMin("0") BigDecimal minimumOrderQuantity,
            @NotBlank String approvedStatus,
            @NotNull @Positive Integer priorityRank,
            boolean isAlternative,
            @NotNull LocalDate validFrom,
            LocalDate validTo
    ) {}

    public record InventorySnapshotUpsertRequest(
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank @Schema(example = "WH-ICN-01") String erpWarehouseId,
            @NotNull @DecimalMin("0") BigDecimal onHandQuantity,
            @NotNull @DecimalMin("0") BigDecimal reservedQuantity,
            @NotNull @DecimalMin("0") BigDecimal blockedQuantity,
            @NotNull @DecimalMin("0") BigDecimal qualityHoldQuantity,
            @NotNull @DecimalMin("0") BigDecimal safetyStockQuantity,
            @NotBlank String sourceUnit,
            @NotBlank String normalizedUnit,
            @NotBlank String dataQualityFlag
    ) {}

    public record MaterialConsumptionUpsertRequest(
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank String plantCode,
            @DecimalMin("0") BigDecimal averageDailyUsage,
            @NotNull @Positive Integer calculationWindowDays,
            @NotBlank String dataQualityFlag
    ) {}

    public record PurchaseOrderUpsertRequest(
            @NotBlank @Schema(example = "PO-0001") String erpPurchaseOrderId,
            @NotBlank String poNumber,
            @NotBlank @Schema(example = "SUP-CHL-01") String erpSupplierId,
            @NotNull LocalDate orderDate,
            @NotBlank String currency,
            @NotBlank String orderStatus,
            @NotBlank String transportMode,
            String portOfEntry
    ) {}

    public record PurchaseOrderItemUpsertRequest(
            @NotBlank @Schema(example = "POI-0001") String erpPurchaseOrderItemId,
            @NotBlank @Schema(example = "PO-0001") String erpPurchaseOrderId,
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank @Schema(example = "CTR-001") String erpContractId,
            @NotNull @DecimalMin("0") BigDecimal orderedQuantity,
            @NotNull @DecimalMin("0") BigDecimal receivedQuantity,
            @NotBlank String unit,
            LocalDate expectedArrivalDate,
            LocalDate confirmedArrivalDate,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @NotBlank String incoterm,
            @NotBlank @Schema(example = "WH-ICN-01") String erpWarehouseId
    ) {}

    public record GoodsReceiptUpsertRequest(
            @NotBlank @Schema(example = "GR-0001") String erpGoodsReceiptId,
            @NotBlank @Schema(example = "POI-0001") String erpPurchaseOrderItemId,
            @NotNull @Positive BigDecimal receivedQuantity,
            @NotNull OffsetDateTime receivedAt,
            @NotBlank @Schema(example = "WH-ICN-01") String erpWarehouseId,
            @NotBlank String qualityStatus,
            @NotBlank String batchNumber,
            OffsetDateTime qualityReleasedAt
    ) {}
}

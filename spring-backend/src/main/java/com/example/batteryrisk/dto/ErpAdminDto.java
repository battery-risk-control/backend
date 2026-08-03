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

    /**
     * 재고 스냅샷 <b>이력 적재</b>용. {@link InventorySnapshotUpsertRequest}(일일 갱신)와 달리
     * 파일이 가진 스냅샷 ID·시각·현재여부를 그대로 보존한다.
     *
     * <p>일일 갱신 API는 "새 스냅샷 하나를 현재로 올리고 이전 것을 내린다"가 목적이라 ID를 새로
     * 채번하고 {@code snapshot_at = CURRENT_TIMESTAMP}, {@code is_current = TRUE}로 고정한다.
     * 그 경로로 이력 CSV 120행을 넣으면 120건이 전부 "지금" 시각으로 찍히고 마지막 행만 현재로
     * 남아 시계열이 사라진다. 그래서 일괄 적재(데이터 관리 화면)는 이 요청을 쓴다.
     */
    public record InventorySnapshotImportRequest(
            @NotBlank @Schema(example = "INV-0001") String erpInventorySnapshotId,
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank @Schema(example = "WH-ICN-01") String erpWarehouseId,
            @NotNull @DecimalMin("0") BigDecimal onHandQuantity,
            @NotNull @DecimalMin("0") BigDecimal reservedQuantity,
            @NotNull @DecimalMin("0") BigDecimal blockedQuantity,
            @NotNull @DecimalMin("0") BigDecimal qualityHoldQuantity,
            @NotNull @DecimalMin("0") BigDecimal safetyStockQuantity,
            @NotNull OffsetDateTime snapshotAt,
            boolean isCurrent,
            @NotBlank String sourceUnit,
            @NotBlank String normalizedUnit,
            @NotBlank String dataQualityFlag
    ) {}

    /** {@link InventorySnapshotImportRequest}와 같은 이유·같은 용도, 소비량 이력 전용. */
    public record MaterialConsumptionImportRequest(
            @NotBlank @Schema(example = "CON-001") String erpConsumptionId,
            @NotBlank @Schema(example = "MAT-LI-CARB") String erpMaterialId,
            @NotBlank String plantCode,
            @DecimalMin("0") BigDecimal averageDailyUsage,
            @NotNull @Positive Integer calculationWindowDays,
            @NotNull OffsetDateTime calculatedAt,
            boolean isCurrent,
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

    // --- 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 전용, 인바운드 테이블과 완전히 분리 ---

    public record ProductUpsertRequest(
            @NotBlank @Schema(example = "PROD-001") String erpProductId,
            @NotBlank String nameEn,
            @NotBlank String nameKr,
            @NotBlank String productLine
    ) {}

    public record CustomerUpsertRequest(
            @NotBlank @Schema(example = "CUST-001") String erpCustomerId,
            @NotBlank String nameEn,
            @NotBlank String nameKr,
            @NotBlank String countryCode
    ) {}

    public record OutboundContractUpsertRequest(
            @NotBlank @Schema(example = "CTR-OUT-001") String erpOutboundContractId,
            @NotBlank @Schema(example = "PROD-001") String erpProductId,
            @NotBlank @Schema(example = "CUST-001") String erpCustomerId,
            @NotBlank String seller,
            @NotNull @DecimalMin("0") BigDecimal quantityGwh,
            @NotNull @DecimalMin("0") BigDecimal unitPriceUsdKwh,
            @NotNull @DecimalMin("0") BigDecimal penaltyPct,
            BigDecimal lineStopChargeUsd,
            BigDecimal lineStopChargeKrw,
            @NotNull @Positive Integer deliveryLeadTimeDays,
            @NotBlank String contractLanguage
    ) {}
}

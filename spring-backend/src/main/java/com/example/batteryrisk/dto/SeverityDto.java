package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 11단계 Severity 외부 API, FastAPI 내부 통신, 저장 결과 DTO를 한 파일에 모읍니다. */
public final class SeverityDto {
    private SeverityDto() {}

    public record AssessmentRequest(
            @NotBlank
            @Schema(example = "MAT-LI-CARB")
            String erpMaterialId,

            @Schema(example = "SUP-CHL-01")
            String erpSupplierId,

            @NotNull
            @Schema(example = "2026-07-22T12:00:00+09:00")
            OffsetDateTime asOf,

            @Schema(example = "11.5", description = "가격 변동률(%) 또는 null")
            BigDecimal priceChangeRate,

            @DecimalMin("0.0")
            @Schema(example = "7", description = "물류 지연 일수 또는 null")
            BigDecimal logisticsDelayDays,

            @Min(0) @Max(2)
            @Schema(example = "2", description = "GDACS 0/1/2 또는 null")
            Integer gdacsAlertLevel
    ) {}

    public record FastApiRequest(
            @JsonProperty("inventory_days") BigDecimal inventoryDays,
            @JsonProperty("safety_stock_days") BigDecimal safetyStockDays,
            @JsonProperty("expected_supply_gap_days") BigDecimal expectedSupplyGapDays,
            @JsonProperty("supplier_dependency_ratio") BigDecimal supplierDependencyRatio,
            @JsonProperty("price_change_rate") BigDecimal priceChangeRate,
            @JsonProperty("logistics_delay_days") BigDecimal logisticsDelayDays,
            @JsonProperty("gdacs_alert_level") Integer gdacsAlertLevel,
            @JsonProperty("feoc_status") String feocStatus,
            @JsonProperty("data_quality_status") String dataQualityStatus
    ) {}

    public record FastApiResponse(
            boolean success,
            FastApiResult data,
            OffsetDateTime timestamp
    ) {}

    public record FastApiResult(
            String severity,
            BigDecimal score,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("calculation_details") Map<String, Object> calculationDetails,
            @JsonProperty("rule_version") String ruleVersion,
            boolean mock
    ) {}

    public record AssessmentResponse(
            UUID assessmentId,
            Long materialId,
            Long supplierId,
            String erpMaterialId,
            String erpSupplierId,
            OffsetDateTime assessedAt,
            BigDecimal inventoryDays,
            BigDecimal safetyStockDays,
            BigDecimal expectedSupplyGapDays,
            BigDecimal supplierDependencyRatio,
            BigDecimal priceChangeRate,
            BigDecimal logisticsDelayDays,
            Integer gdacsAlertLevel,
            String feocStatus,
            String dataQualityStatus,
            String severity,
            BigDecimal score,
            List<String> reasonCodes,
            Map<String, Object> calculationDetails,
            String ruleVersion,
            boolean mock,
            OffsetDateTime createdAt
    ) {}
}

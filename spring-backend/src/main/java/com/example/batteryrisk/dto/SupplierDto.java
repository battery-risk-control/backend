package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/** F9 공급사 자격·대체 공급사 추천에서 쓰는 DTO 모음입니다. */
public final class SupplierDto {
    private SupplierDto() {}

    /** Spring이 필수조건(APPROVED/ACTIVE/FEOC 적합)을 통과한 공급사만 걸러서 보내는 후보 정보입니다. */
    public record SupplierCandidate(
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("supplier_code") String supplierCode,
            @JsonProperty("supplier_name") String supplierName,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("feoc_status") String feocStatus,
            String certifications,
            @JsonProperty("risk_level") String riskLevel,
            @JsonProperty("supplier_status") String supplierStatus,
            @JsonProperty("lead_time_days") Integer leadTimeDays,
            @JsonProperty("minimum_order_quantity") BigDecimal minimumOrderQuantity,
            @JsonProperty("supply_share_ratio") BigDecimal supplyShareRatio,
            @JsonProperty("priority_rank") Integer priorityRank,
            @JsonProperty("is_alternative") boolean isAlternative,
            @JsonProperty("latest_unit_price") BigDecimal latestUnitPrice,
            @JsonProperty("latest_order_date") String latestOrderDate
    ) {}

    public record QualifiedSuppliersResponse(
            @JsonProperty("material_category") String materialCategory,
            List<SupplierCandidate> candidates
    ) {}
}

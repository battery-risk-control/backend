package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 아웃바운드 계약서 업로드(LG에너지솔루션 -> 완성차/ESS 고객사, 2단계: 미리보기 → 확정,
 * CTR-OUT-XXX 자동 발급)에서 쓰는 DTO 모음입니다. {@link ContractUploadDto}의 아웃바운드
 * 버전 — supplier/material 대신 product/customer 기준이고, 발효일/만료일 대신 물량/단가/
 * 납기일수/위약금을 다룹니다(아웃바운드 계약엔 발효일/만료일 개념 자체가 없음).
 */
public final class OutboundContractUploadDto {
    private OutboundContractUploadDto() {}

    /** 1단계 미리보기 응답 — DB에는 아무것도 쓰지 않는다. */
    public record PreviewResponse(
            @JsonProperty("erp_product_id") String erpProductId,
            @JsonProperty("erp_customer_id") String erpCustomerId,
            @JsonProperty("existing_contract_id") String existingContractId,
            @JsonProperty("expected_new_contract_id") String expectedNewContractId,
            @JsonProperty("contract_language") String contractLanguage,
            @JsonProperty("quantity_gwh") BigDecimal quantityGwh,
            @JsonProperty("unit_price_usd_kwh") BigDecimal unitPriceUsdKwh,
            @JsonProperty("delivery_lead_time_days") Integer deliveryLeadTimeDays,
            @JsonProperty("penalty_pct") BigDecimal penaltyPct
    ) {}

    /** 2단계 확정 응답. */
    public record ConfirmResponse(
            @JsonProperty("contract_id") String erpOutboundContractId,
            @JsonProperty("contract_created") boolean contractCreated,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("processing_status") String processingStatus,

            /**
             * KG 동기화 실패 사유. 성공했거나 동기화 대상이 아니면 null이다.
             * 업로드는 됐는데 지식그래프만 과거에 머무는 상태를 화면이 알려야 한다.
             */
            @JsonProperty("kg_sync_warning") String kgSyncWarning
    ) {}
}

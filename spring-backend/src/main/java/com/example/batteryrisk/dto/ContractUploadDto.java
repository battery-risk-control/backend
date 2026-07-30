package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** 계약서 업로드(2단계: 미리보기 → 확정, CTR-XXX 자동 발급 작업)에서 쓰는 DTO 모음입니다. */
public final class ContractUploadDto {
    private ContractUploadDto() {}

    /** 1단계 미리보기 응답 — DB에는 아무것도 쓰지 않는다. */
    public record PreviewResponse(
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("existing_contract_id") String existingContractId,
            @JsonProperty("expected_new_contract_id") String expectedNewContractId,
            @JsonProperty("contract_number") String contractNumber,
            @JsonProperty("contract_name") String contractName,
            @JsonProperty("effective_date") LocalDate effectiveDate,
            @JsonProperty("expiration_date") LocalDate expirationDate
    ) {}

    /** 2단계 확정 응답. */
    public record ConfirmResponse(
            @JsonProperty("contract_id") String erpContractId,
            @JsonProperty("contract_created") boolean contractCreated,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("processing_status") String processingStatus
    ) {}
}

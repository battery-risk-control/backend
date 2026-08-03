package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** 계약서 업로드(2단계: 미리보기 → 확정, CTR-XXX 자동 발급 작업)에서 쓰는 DTO 모음입니다. */
public final class ContractUploadDto {
    private ContractUploadDto() {}

    /**
     * 1단계 미리보기 응답 — DB에는 아무것도 쓰지 않는다.
     *
     * <p>{@code file_name} 이하는 데이터 관리 화면의 "내용 분석" 칸용이다. 미리보기는 이미 파일
     * 원문을 뽑아 필드를 추출하는데 그 원문을 버리고 있었다 — 사용자가 DB에 반영하기 전에
     * "무엇이 읽혔는지"를 볼 방법이 필요해서 그대로 실어 보낸다.
     */
    public record PreviewResponse(
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("existing_contract_id") String existingContractId,
            @JsonProperty("expected_new_contract_id") String expectedNewContractId,
            @JsonProperty("contract_number") String contractNumber,
            @JsonProperty("contract_name") String contractName,
            @JsonProperty("effective_date") LocalDate effectiveDate,
            @JsonProperty("expiration_date") LocalDate expirationDate,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("size_bytes") long sizeBytes,
            /** 추출된 원문 전체 글자 수. 미리보기 문자열은 잘려 있으므로 이 값으로 분량을 가늠한다. */
            @JsonProperty("char_count") int charCount,
            /** 원문 앞부분. 화면에 그대로 뿌린다. */
            @JsonProperty("text_preview") String textPreview,
            /**
             * 원문 추출 성공 여부. 실패해도 미리보기 자체는 진행되므로(사용자가 필드를 직접
             * 입력하면 된다), 화면이 "빈 문서"와 "읽지 못함"을 구분하려면 이 값이 필요하다.
             */
            @JsonProperty("text_extracted") boolean textExtracted
    ) {}

    /** 2단계 확정 응답. */
    public record ConfirmResponse(
            @JsonProperty("contract_id") String erpContractId,
            @JsonProperty("contract_created") boolean contractCreated,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("processing_status") String processingStatus
    ) {}
}

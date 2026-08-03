package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** 계약서 업로드(2단계: 미리보기 → 확정, CTR-XXX 자동 발급 작업)에서 쓰는 DTO 모음입니다. */
public final class ContractUploadDto {
    private ContractUploadDto() {}

    /**
     * 업로드 대상을 고르는 드롭다운 선택지.
     *
     * <p><b>계약 목록이 아니라 공급사·자재 목록인 이유:</b> 이 화면의 목적은 아직 계약이 없는
     * 조합에 <b>새 계약서를 처음 등록</b>하는 것이다. 계약 목록으로 고르게 하면 "이미 계약이 있는
     * 조합"밖에 못 고르니 신규 등록 자체가 불가능하다. 공급사·자재를 직접 고르게 하면 그 조합에
     * 계약이 있든 없든 선택할 수 있고, 있는지 없는지는 미리보기가 알려준다.
     */
    public record UploadOptions(
            java.util.List<SupplierOption> suppliers,
            java.util.List<MaterialOption> materials
    ) {}

    /** 거래 상태를 함께 준다 — 중단된 공급사도 과거 계약서를 올릴 일이 있어 목록에서 빼지 않는다. */
    public record SupplierOption(
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("supplier_name") String supplierName,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("supplier_status") String supplierStatus
    ) {}

    public record MaterialOption(
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("material_name") String materialName,
            @JsonProperty("material_category") String materialCategory,
            boolean active
    ) {}

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
            @JsonProperty("processing_status") String processingStatus,

            /**
             * KG 동기화 실패 사유. 성공했거나 동기화 대상이 아니면 null이다.
             *
             * <p>업로드는 성공했는데 지식그래프만 과거에 머무는 상태를 화면이 알려야 한다 —
             * 예전엔 로그에만 남아서 그 뒤 리졸브가 낡은 재고로 계산되는 걸 아무도 몰랐다.
             */
            @JsonProperty("kg_sync_warning") String kgSyncWarning
    ) {}
}

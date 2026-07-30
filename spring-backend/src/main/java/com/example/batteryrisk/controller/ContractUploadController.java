package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ContractUploadDto;
import com.example.batteryrisk.service.ContractUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 계약서 업로드 → CTR-XXX 자동 발급(계획서 "부속 작업 2"). 문서유형=계약서를 고른 경우 프론트가
 * 기존 {@link DocumentController}(/api/v1/documents) 대신 이쪽을 호출한다 — 공급사+자재만 고르면
 * (기존 contract_id를 몰라도) 알아서 기존 계약에 붙이거나 새 계약을 만든다.
 *
 * 1단계 /preview: DB에 아무것도 안 쓰고 파일에서 필드를 추출해 미리 보여준다.
 * 2단계 /confirm: 사용자가 미리보기 결과를 확인/수정한 뒤 실제로 생성한다.
 */
@RestController
@RequestMapping("/api/v1/documents/contracts")
@SecurityRequirement(name = "bearerAuth")
public class ContractUploadController {
    private final ContractUploadService service;

    public ContractUploadController(ContractUploadService service) {
        this.service = service;
    }

    @Operation(
            summary = "계약서 업로드 미리보기",
            description = "파일에서 계약번호/계약명/발효일/만료일을 정규식으로 추출해 보여준다. DB에는 쓰지 않는다.")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContractUploadDto.PreviewResponse> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam("erp_supplier_id") String erpSupplierId,
            @RequestParam("erp_material_id") String erpMaterialId) {
        return ApiResponse.ok(service.preview(file, erpSupplierId, erpMaterialId));
    }

    @Operation(
            summary = "계약서 업로드 확정",
            description = "미리보기에서 확인/수정한 값으로 계약(필요시)+공급관계(필요시)+문서를 실제로 생성한다.")
    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContractUploadDto.ConfirmResponse> confirm(
            @RequestPart("file") MultipartFile file,
            @RequestParam("erp_supplier_id") String erpSupplierId,
            @RequestParam("erp_material_id") String erpMaterialId,
            @RequestParam(value = "contract_number", required = false) String contractNumber,
            @RequestParam(value = "contract_name", required = false) String contractName,
            @RequestParam(value = "effective_date", required = false) LocalDate effectiveDate,
            @RequestParam(value = "expiration_date", required = false) LocalDate expirationDate) {
        return ApiResponse.ok(service.confirm(
                file, erpSupplierId, erpMaterialId,
                contractNumber, contractName, effectiveDate, expirationDate));
    }
}

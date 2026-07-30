package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.OutboundContractUploadDto;
import com.example.batteryrisk.service.OutboundContractUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

/**
 * 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 계약서 업로드 → CTR-OUT-XXX 자동 발급.
 * {@link ContractUploadController}의 아웃바운드 버전 — 공급사+자재 대신 제품+고객사 기준이다.
 *
 * 1단계 /preview: DB에 아무것도 안 쓰고 파일에서 필드를 추출해 미리 보여준다.
 * 2단계 /confirm: 제품+고객사+파일만 주면(다른 필드는 비워도) 알아서 기존 계약에 붙이거나
 * 새 계약을 만든다.
 */
@RestController
@RequestMapping("/api/v1/documents/outbound-contracts")
@SecurityRequirement(name = "bearerAuth")
public class OutboundContractUploadController {
    private final OutboundContractUploadService service;

    public OutboundContractUploadController(OutboundContractUploadService service) {
        this.service = service;
    }

    @Operation(
            summary = "아웃바운드 계약서 업로드 미리보기",
            description = "파일에서 계약언어/물량/단가/납기일수/위약금을 정규식으로 추출해 보여준다. DB에는 쓰지 않는다.")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OutboundContractUploadDto.PreviewResponse> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam("erp_product_id") String erpProductId,
            @RequestParam("erp_customer_id") String erpCustomerId) {
        return ApiResponse.ok(service.preview(file, erpProductId, erpCustomerId));
    }

    @Operation(
            summary = "아웃바운드 계약서 업로드 확정",
            description = "미리보기에서 확인/수정한 값으로 계약(필요시)+문서를 실제로 생성한다.")
    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OutboundContractUploadDto.ConfirmResponse> confirm(
            @RequestPart("file") MultipartFile file,
            @RequestParam("erp_product_id") String erpProductId,
            @RequestParam("erp_customer_id") String erpCustomerId,
            @RequestParam(value = "contract_language", required = false) String contractLanguage,
            @RequestParam(value = "quantity_gwh", required = false) BigDecimal quantityGwh,
            @RequestParam(value = "unit_price_usd_kwh", required = false) BigDecimal unitPriceUsdKwh,
            @RequestParam(value = "delivery_lead_time_days", required = false) Integer deliveryLeadTimeDays,
            @RequestParam(value = "penalty_pct", required = false) BigDecimal penaltyPct,
            @RequestParam(value = "line_stop_charge_usd", required = false) BigDecimal lineStopChargeUsd,
            @RequestParam(value = "line_stop_charge_krw", required = false) BigDecimal lineStopChargeKrw) {
        return ApiResponse.ok(service.confirm(
                file, erpProductId, erpCustomerId, contractLanguage, quantityGwh, unitPriceUsdKwh,
                deliveryLeadTimeDays, penaltyPct, lineStopChargeUsd, lineStopChargeKrw));
    }
}

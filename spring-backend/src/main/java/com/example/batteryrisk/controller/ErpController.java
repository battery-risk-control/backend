package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.service.ErpExposureRequestService;
import com.example.batteryrisk.service.ErpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/erp")
@SecurityRequirement(name = "bearerAuth")
public class ErpController {
    private final ErpService erpService;
    private final ErpExposureRequestService erpExposureRequestService;

    public ErpController(ErpService erpService, ErpExposureRequestService erpExposureRequestService) {
        this.erpService = erpService;
        this.erpExposureRequestService = erpExposureRequestService;
    }

    @Operation(
            summary = "F1 ERP 영향 계산용 Context 생성",
            description = "외부 ERP 자재·공급사 ID를 내부 PK로 매핑하고 재고·소비·발주 수치를 결정적으로 계산합니다."
    )
    @PostMapping("/context")
    public ApiResponse<ErpDto.ContextResponse> context(
            @Valid @RequestBody ErpDto.ContextRequest request) {
        return ApiResponse.ok(erpService.buildContext(request));
    }

    // [surin F9] ERP Exposure Agent 수동 호출 트리거. 자동 F9 흐름은 AnalysisService가
    // ErpExposureRequestService를 직접 호출하므로 이 엔드포인트는 수동 테스트/검증용이다.
    @Operation(
            summary = "ERP Exposure Agent 호출 (임시: 자재 대분류 내 대표 자재 1개만 사용)",
            description = "ERP Exposure Agent(POST /api/v1/internal/erp/exposure)를 호출합니다."
    )
    @PostMapping("/exposure")
    public ApiResponse<ErpExposureDto.ExposureResponse> exposure(
            @RequestBody ErpExposureDto.ExposureTriggerRequest request) {
        return ApiResponse.ok(erpExposureRequestService.analyzeExposure(request));
    }
}

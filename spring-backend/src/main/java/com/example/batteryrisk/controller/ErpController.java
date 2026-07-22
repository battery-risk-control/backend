package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ErpDto;
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

    public ErpController(ErpService erpService) {
        this.erpService = erpService;
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
}

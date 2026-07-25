package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.BriefingDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.service.BriefingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/briefings")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BriefingController {
    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    @Operation(
            summary = "13단계 템플릿 브리핑 생성 및 저장",
            description = "ERP 영향·Severity·계약 근거·적격 대체 공급사를 모아 템플릿 브리핑을 생성하고 저장합니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<BriefingDto.BriefingResponse>> generate(
            @Valid @RequestBody BriefingDto.GenerateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(briefingService.generate(request)));
    }

    @Operation(
            summary = "브리핑 목록 조회",
            description = "최신순 페이지네이션. severity·erp_material_id로 필터할 수 있습니다.")
    @GetMapping
    public ApiResponse<PageResponse<BriefingDto.BriefingListItem>> list(
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "erp_material_id", required = false) String erpMaterialId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(briefingService.list(severity, erpMaterialId, page, size));
    }

    @Operation(summary = "저장된 브리핑 조회")
    @GetMapping("/{briefingId}")
    public ApiResponse<BriefingDto.BriefingResponse> get(@PathVariable UUID briefingId) {
        return ApiResponse.ok(briefingService.get(briefingId));
    }
}

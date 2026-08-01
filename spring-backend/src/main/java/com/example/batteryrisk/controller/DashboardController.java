package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 14단계 조회·집계 API.
 *
 * <p>3계층(구매팀·경영기획팀·경영진)이 같은 데이터를 각자 다른 수준으로 보므로,
 * 현재는 로그인한 사용자면 모두 조회할 수 있게 두고 화면에서 표시 범위를 조정한다.
 * 계층별 API 제한이 필요해지면 여기에 {@code @PreAuthorize}를 추가한다.
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(
            summary = "대시보드 요약 집계",
            description = "자재별 최신 Severity를 기준으로 등급별 건수와 전체 데이터 규모를 반환합니다.")
    @GetMapping("/dashboard/summary")
    public ApiResponse<DashboardDto.Summary> summary() {
        return ApiResponse.ok(dashboardService.summary());
    }

    @Operation(
            summary = "구매 리스크 KPI 요약(멀티에이전트)",
            description = "자재 대분류(8종)별 최신 구매 리스크 평가 1건을 기준으로 등급별 건수, "
                    + "ERP노출도·외부신호 평균 점수, 검증 통과 브리핑 건수를 반환합니다.")
    @GetMapping("/dashboard/procurement-risk-summary")
    public ApiResponse<DashboardDto.ProcurementRiskSummary> procurementRiskSummary() {
        return ApiResponse.ok(dashboardService.procurementRiskSummary());
    }

    @Operation(
            summary = "자재별 현재 리스크 목록",
            description = "자재별 최신 Severity를 심각도 순으로 반환합니다. 리스크 게이지·스코어 카드용입니다.")
    @GetMapping("/dashboard/materials")
    public ApiResponse<List<DashboardDto.MaterialRiskItem>> materialRisks(
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(dashboardService.materialRisks(severity, limit));
    }

    @Operation(
            summary = "자재별 공급사 의존도 분해",
            description = "수입 의존도 도넛 차트용. 색상은 화면에서 지정합니다.")
    @GetMapping("/dashboard/import-dependency")
    public ApiResponse<DashboardDto.ImportDependency> importDependency(
            @RequestParam(name = "erp_material_id") String erpMaterialId,
            @RequestParam(name = "as_of", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf) {
        return ApiResponse.ok(dashboardService.importDependency(erpMaterialId, asOf));
    }

    @Operation(summary = "계약 목록 조회", description = "status로 필터할 수 있는 페이지네이션 목록입니다.")
    @GetMapping("/contracts")
    public ApiResponse<PageResponse<DashboardDto.ContractItem>> contracts(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(dashboardService.contracts(status, page, size));
    }
}

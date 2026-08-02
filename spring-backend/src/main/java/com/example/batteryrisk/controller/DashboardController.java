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

    /**
     * 1계층 구매팀 대시보드 상단 KPI 5칸(심각·주의 건수, ERP 영향도, 외부 위험, 검증 브리핑).
     *
     * <p>경로를 {@code /dashboard/procurement-risk-summary}에서 옮겼다 — 나머지 1계층 화면 API가
     * 화면 단위로 이름을 갖는데({@code /risk-monitoring/**}, {@code /material-risk/**},
     * {@code /ai-briefing/**}) 이것만 계층 구분 없는 {@code /dashboard/**} 아래 있어,
     * 어느 화면이 쓰는 집계인지 경로만 봐서는 알 수 없었다. 같은 {@code /dashboard/**}에 있는
     * {@code summary}는 {@code severity_assessments} 기반이라 모집단부터 다르다.
     *
     * <p>구 경로도 함께 매핑해 둔다. 이미 Postman·문서·팀원 코드가 그 경로를 부르고 있어
     * 한쪽만 남기면 조용히 404가 나기 때문이다. 신규 호출은 새 경로를 쓴다.
     */
    @Operation(
            summary = "구매팀 대시보드 KPI 요약(멀티에이전트)",
            description = "자재 대분류(8종)별 최신 구매 리스크 평가 1건을 기준으로 등급별 건수, "
                    + "ERP노출도·외부신호 평균 점수, 검증 통과 브리핑 건수를 반환합니다. "
                    + "구 경로 /api/v1/dashboard/procurement-risk-summary 도 같은 결과를 반환합니다(하위 호환).")
    @GetMapping({"/purchasing-dashboard/kpi-summary", "/dashboard/procurement-risk-summary"})
    public ApiResponse<DashboardDto.ProcurementRiskSummary> procurementRiskSummary() {
        return ApiResponse.ok(dashboardService.procurementRiskSummary());
    }

    @Operation(
            summary = "원자재별 리스크 요약(7종, 최종 합성 점수)",
            description = """
                    배터리 원자재 7종(알루미늄·코발트·구리·흑연·리튬·망간·니켈) 각각에 대해
                    점수 상위 3건의 procurement_risk_score 평균과 그중 최고 등급을 반환합니다.
                    이 점수는 외부신호·ERP노출·계약공백을 모두 합친 최종 합성 점수로,
                    /dashboard/materials 의 ERP 노출도 단독 점수와 다릅니다.

                    score_delta는 24시간 전까지 쌓여 있던 평가만으로 같은 계산을 한 값과의 차이입니다.
                    그때까지 평가가 없던 자재는 null이며, 화면은 증감을 표시하지 않습니다.

                    latest_assessment_id는 완료 처리(POST /api/v1/multi-agent/assessments/{id}/acknowledge)
                    대상입니다 — KPI 건수가 세는 "대분류별 최신 1건"이라 상위 3건과는 기준이 다릅니다.

                    평가가 0건인 자재도 행을 반환합니다(점수·등급 null).""")
    @GetMapping("/purchasing-dashboard/material-risk-summary")
    public ApiResponse<List<DashboardDto.MaterialRiskSummaryItem>> materialRiskSummary() {
        return ApiResponse.ok(dashboardService.materialRiskSummary());
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

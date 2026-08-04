package com.example.batteryrisk.controller.executive;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.executive.ExecutiveDashboardDto;
import com.example.batteryrisk.service.executive.ExecutiveDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3계층 경영진 대시보드 조회 API.
 */
@RestController
@RequestMapping("/api/v1/executive")
@SecurityRequirement(name = "bearerAuth")
public class ExecutiveDashboardController {
    private final ExecutiveDashboardService
            executiveDashboardService;

    public ExecutiveDashboardController(
            ExecutiveDashboardService
                    executiveDashboardService
    ) {
        this.executiveDashboardService =
                executiveDashboardService;
    }

    @Operation(
            summary = "경영진 통합 대시보드",
            description = """
                    경영 KPI, 세계 위험 지도, 핵심 원자재 위험,
                    최근 30일 위험 추세, 국가별 수입 의존도,
                    공급사 위험, 최근 AI 브리핑,
                    멀티에이전트 검증 현황을 반환합니다.
                    """
    )
    @GetMapping("/dashboard")
    public ApiResponse<ExecutiveDashboardDto.Dashboard>
    dashboard() {
        return ApiResponse.ok(
                executiveDashboardService.dashboard()
        );
    }
}
package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.service.PlanningDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2계층(경영기획팀, 코드상 Role.STRATEGY) 대시보드 7탭 조회·집계 API.
 *
 * <p>프론트엔드 {@code docs/mock-schemas.md}가 제안했던 {@code /api/planning/dashboard}
 * (버전 접두사 없음)는 이 코드베이스의 실제 컨벤션({@code /api/v1/...})과 달라
 * {@code /api/v1/planning/...}로 교정했다 — 프론트엔드 문서/구현은 실 연동 시 이 경로로
 * 맞춰야 한다.
 *
 * <p>{@link DashboardController}와 동일하게 로그인한 사용자면 모두 조회할 수 있게 두고
 * 화면에서 표시 범위를 조정한다(이 코드베이스에 아직 역할별 {@code @PreAuthorize} 게이팅
 * 패턴이 없어 새로 들여오지 않기로 결정 — 2026-08-02).
 */
@RestController
@RequestMapping("/api/v1/planning")
@SecurityRequirement(name = "bearerAuth")
public class PlanningDashboardController {
    private final PlanningDashboardService planningDashboardService;

    public PlanningDashboardController(PlanningDashboardService planningDashboardService) {
        this.planningDashboardService = planningDashboardService;
    }

    @Operation(summary = "전략 대시보드", description = "사업부별 리스크 노출도, 협력사 리스크 이력, 이번 분기 KPI 요약.")
    @GetMapping("/strategy-dashboard")
    public ApiResponse<PlanningDashboardDto.StrategyDashboard> strategyDashboard() {
        return ApiResponse.ok(planningDashboardService.strategyDashboard());
    }

    @Operation(summary = "자재 위험", description = "자재별 위험 순위, 사업부별 자재 노출도, 분기 대비 추이.")
    @GetMapping("/material-risk")
    public ApiResponse<PlanningDashboardDto.MaterialRiskDashboard> materialRisk() {
        return ApiResponse.ok(planningDashboardService.materialRisk());
    }

    @Operation(summary = "수입 의존도", description = "국가별·사업부별 수입 의존도, 다변화 진행률, 대체 공급사 후보.")
    @GetMapping("/import-dependency")
    public ApiResponse<PlanningDashboardDto.ImportDependencyDashboard> importDependency() {
        return ApiResponse.ok(planningDashboardService.importDependency());
    }

    @Operation(summary = "공급사 분석", description = "공급사별 리스크 이력 랭킹, 연결 사업부, 대체 검토 진행률.")
    @GetMapping("/supplier-analysis")
    public ApiResponse<PlanningDashboardDto.SupplierAnalysisDashboard> supplierAnalysis() {
        return ApiResponse.ok(planningDashboardService.supplierAnalysis());
    }

    @Operation(summary = "계약 현황", description = "계약 상태·만료 임박·문서 적재 현황, 사업부별 계약 커버리지.")
    @GetMapping("/contracts")
    public ApiResponse<PlanningDashboardDto.ContractStatusDashboard> contracts() {
        return ApiResponse.ok(planningDashboardService.contractStatus());
    }

    @Operation(summary = "AI 브리핑", description = "사업부별 브리핑 건수, 최근 브리핑 목록, 이번 분기 KPI 요약.")
    @GetMapping("/ai-briefing")
    public ApiResponse<PlanningDashboardDto.AiBriefingSummaryDashboard> aiBriefing() {
        return ApiResponse.ok(planningDashboardService.aiBriefing());
    }

    @Operation(summary = "데이터 품질", description = "ERP 적재 상태, RAG 인덱스 상태, 자재 커버리지, 신뢰도 라벨 분포.")
    @GetMapping("/data-quality")
    public ApiResponse<PlanningDashboardDto.DataQualityStatus> dataQuality() {
        return ApiResponse.ok(planningDashboardService.dataQuality());
    }
}

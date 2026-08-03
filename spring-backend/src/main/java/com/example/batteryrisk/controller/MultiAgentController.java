package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.security.CustomUserDetails;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/multi-agent")
@SecurityRequirement(name = "bearerAuth")
public class MultiAgentController {
    private final MultiAgentOrchestrationService service;

    public MultiAgentController(
            MultiAgentOrchestrationService service
    ) {
        this.service = service;
    }

    @Operation(
            summary = "멀티에이전트 구매 리스크 브리핑 생성",
            description = """
                    Spring이 PostgreSQL ERP 데이터를 조회하고,
                    FastAPI의 ERP Agent, Contract RAG Agent,
                    위험도 계산, 브리핑 생성 및 검증 흐름을 실행합니다.
                    """
    )
    @PostMapping("/briefings")
    public ApiResponse<MultiAgentDto.Response> generate(
        @Valid @RequestBody
        MultiAgentDto.GenerateRequest request
    ) {
        return ApiResponse.ok(
                service.generate(request)
        );
    }

    @Operation(
            summary = "저장된 구매 리스크 점수 조회",
            description = """
                    생성 응답의 assessmentId로 다시 꺼내 봅니다.
                    세부 점수 3개(외부신호·ERP 노출도·계약공백)와 적용된 가중치 버전,
                    판단 근거를 함께 반환하므로 나중에 왜 그 등급이 나왔는지 재현할 수 있습니다.
                    """
    )
    @GetMapping("/assessments/{assessmentId}")
    public ApiResponse<ProcurementRiskDto.Assessment> getAssessment(
            @PathVariable UUID assessmentId
    ) {
        return ApiResponse.ok(service.getAssessment(assessmentId));
    }

    @Operation(
            summary = "구매 리스크 평가 완료 처리",
            description = """
                    해당 평가를 처리 완료로 표시합니다. 원본 procurement_risk_assessments 행은
                    바꾸지 않고 별도 로그에만 남기며(append-only 유지), 대시보드 KPI 요약
                    (/api/v1/purchasing-dashboard/kpi-summary)의 심각/주의 집계에서 이 평가가
                    그 자재 대분류의 최신 상태였다면 제외됩니다. 이후 같은 자재에 새 평가가
                    들어오면 자동으로 다시 집계됩니다.
                    """
    )
    @PostMapping("/assessments/{assessmentId}/acknowledge")
    public ApiResponse<ProcurementRiskDto.AcknowledgeResponse> acknowledge(
            @PathVariable UUID assessmentId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ApiResponse.ok(service.acknowledgeAssessment(assessmentId, userDetails.getId()));
    }

    @Operation(
            summary = "구매 리스크 평가 완료 처리 되돌리기",
            description = """
                    완료 처리를 취소해 그 평가를 대시보드 집계로 되돌립니다. 완료 기록만 지우며
                    원본 procurement_risk_assessments 행은 처음부터 건드리지 않았으므로 상태가
                    완전히 원래대로 돌아옵니다.

                    되돌릴 기록이 없어도 200입니다 — 결과 상태가 같기 때문입니다. 그 경우
                    not_acknowledged=true로 구분할 수 있습니다. 없는 평가 id면 404입니다.
                    """
    )
    @DeleteMapping("/assessments/{assessmentId}/acknowledge")
    public ApiResponse<ProcurementRiskDto.UnacknowledgeResponse> unacknowledge(
            @PathVariable UUID assessmentId
    ) {
        return ApiResponse.ok(service.unacknowledgeAssessment(assessmentId));
    }

    @Operation(
            summary = "구매 리스크 점수 자동 축적 수동 실행",
            description = """
                    app.risk-scoring.enabled 스케줄러(30분 주기)와 같은 로직을 즉시 1회 실행합니다.
                    스케줄 대기 없이 검증하거나, 스케줄러가 꺼진 상태에서 필요할 때만 수동으로
                    채워 넣을 때 씁니다.
                    """
    )
    @PostMapping("/risk-scoring/run")
    public ApiResponse<RiskScoringRunResult> runRiskScoring() {
        int saved = service.scoreNewAnalyses(10);
        return ApiResponse.ok(new RiskScoringRunResult(saved));
    }

    public record RiskScoringRunResult(int savedCount) {}
}
package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
}
package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

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
}
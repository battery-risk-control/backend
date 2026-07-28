package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.AnalysisDto;
import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses")
@SecurityRequirement(name = "bearerAuth")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Operation(summary = "분석 요청 접수", description = "이벤트를 접수하여 PENDING 저장 후 FastAPI 분석을 호출하고 결과를 저장합니다.")
    @PostMapping
    public ApiResponse<AnalysisDto.AnalysisResponse> create(@Valid @RequestBody AnalysisDto.AnalyzeRequest request) {
        return ApiResponse.ok(analysisService.create(request));
    }

    @Operation(summary = "분석 상태·결과 조회", description = "PostgreSQL에 저장된 분석 상태와 결과를 조회합니다.")
    @GetMapping("/{analysis_id}")
    public ApiResponse<AnalysisDto.AnalysisResponse> get(@PathVariable("analysis_id") String analysisId) {
        return ApiResponse.ok(analysisService.get(analysisId));
    }
}

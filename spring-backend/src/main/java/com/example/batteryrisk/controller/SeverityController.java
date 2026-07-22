package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.service.SeverityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/severity/assessments")
@SecurityRequirement(name = "bearerAuth")
public class SeverityController {
    private final SeverityService severityService;

    public SeverityController(SeverityService severityService) {
        this.severityService = severityService;
    }

    @Operation(summary = "11단계 Severity 분석 실행 및 결과 저장")
    @PostMapping
    public ResponseEntity<ApiResponse<SeverityDto.AssessmentResponse>> assess(
            @Valid @RequestBody SeverityDto.AssessmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(severityService.assess(request)));
    }

    @Operation(summary = "저장된 Severity 분석 결과 조회")
    @GetMapping("/{assessmentId}")
    public ApiResponse<SeverityDto.AssessmentResponse> get(
            @PathVariable UUID assessmentId) {
        return ApiResponse.ok(severityService.get(assessmentId));
    }
}

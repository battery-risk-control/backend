package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.RiskEventDto;
import com.example.batteryrisk.service.RiskEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 구매팀 대시보드 허브 — 리스크 이벤트 목록 조회 API.
 *
 * <p>프론트엔드 {@code fetchRiskEvents()}가 이 엔드포인트로 갈아탈 수 있도록 RiskEvent 계약을
 * 그대로 반환한다. erp_view 등 ERP 내부 상세를 포함하므로 인증(Bearer)을 요구한다
 * (비로그인 공개 지도·뉴스용 축약 응답이 필요해지면 별도 공개 경로로 분리한다).
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class RiskEventController {
    private final RiskEventService riskEventService;

    public RiskEventController(RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    @Operation(
            summary = "리스크 이벤트 목록 조회",
            description = "구매팀 대시보드의 원천 데이터. grade(심각/주의/정상)로 필터할 수 있습니다.")
    @GetMapping("/risk-events")
    public ApiResponse<List<RiskEventDto.RiskEvent>> riskEvents(
            @RequestParam(name = "grade", required = false) String grade,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ApiResponse.ok(riskEventService.list(grade, limit));
    }
}

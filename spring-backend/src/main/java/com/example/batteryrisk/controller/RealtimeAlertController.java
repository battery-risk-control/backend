package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.RealtimeAlertDto;
import com.example.batteryrisk.service.RealtimeAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
public class RealtimeAlertController {
    private final RealtimeAlertService realtimeAlertService;

    public RealtimeAlertController(RealtimeAlertService realtimeAlertService) {
        this.realtimeAlertService = realtimeAlertService;
    }

    @Operation(
            summary = "실시간 공급망 리스크 관제 알림 조회",
            description = "지도 마커, 알림 목록, AI 입력 Feature와 판단 근거를 함께 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/realtime-alerts")
    public RealtimeAlertDto.RealtimeAlertsResponse getRealtimeAlerts() {
        return realtimeAlertService.getRealtimeAlerts();
    }
}

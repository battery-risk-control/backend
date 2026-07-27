package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.RiskEventDto;
import com.example.batteryrisk.service.RiskEventService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 비로그인 공개 화면(Seq 23 글로벌 리스크 관제 지도)용 API.
 *
 * <p>ERP 내부 상세(erp_view·quality_check·rag_view)를 제외한 <b>공개 안전 subset</b>만 반환한다.
 * {@code /api/v1/public/**}는 {@code SecurityConfig}에서 permitAll이라 토큰 없이 접근 가능하다.
 * 데이터 내용은 F3/F4 모델·뉴스 파이프라인 배선 전까지 결정론적 placeholder다(리스크 이벤트 원본과 동일).
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
    private final RiskEventService riskEventService;

    public PublicController(RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    @Operation(
            summary = "글로벌 리스크 관제 지도 (비로그인 공개)",
            description = "지도 마커용 공개 subset. 자재·등급·신뢰도·국가·좌표만 포함하며 ERP 내부 상세는 제외한다.")
    @GetMapping("/risk-board")
    public ApiResponse<List<RiskEventDto.RiskBoardItem>> riskBoard() {
        return ApiResponse.ok(riskEventService.riskBoard());
    }
}

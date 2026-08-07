package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MarketPriceDto;
import com.example.batteryrisk.service.MarketPriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원자재 가격 데이터 수동 갱신(관리자). 평소엔 스케줄러(매일 07:00 전체 + 15분 장중)가 채우지만,
 * 새 종목(예: 희토류 REMX) 추가나 시연 준비처럼 <b>즉시 반영</b>이 필요할 때 전체 구간(180일)을
 * 한 번에 다시 받는다 — 최초 기동 백필은 테이블이 비었을 때만 돌아 이미 데이터가 있으면 안 돈다.
 * ErpAdminController와 같이 로그인(bearerAuth)만 요구한다.
 */
@RestController
@RequestMapping("/api/v1/market-price/admin")
@SecurityRequirement(name = "bearerAuth")
public class MarketPriceAdminController {
    private final MarketPriceService marketPriceService;

    public MarketPriceAdminController(MarketPriceService marketPriceService) {
        this.marketPriceService = marketPriceService;
    }

    @Operation(
            summary = "원자재 가격 전체 갱신",
            description = "FastAPI에서 전체 구간(180일) 가격을 다시 받아 저장한다. 새 종목 추가 후 즉시 반영에 쓴다. "
                    + "실패해도 예외를 던지지 않고 status=FAILED로 응답한다.")
    @PostMapping("/refresh")
    public ApiResponse<MarketPriceDto.RefreshResult> refresh() {
        return ApiResponse.ok(marketPriceService.refresh());
    }
}

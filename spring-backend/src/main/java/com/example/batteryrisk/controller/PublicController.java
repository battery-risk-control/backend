package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MarketPriceDto;
import com.example.batteryrisk.dto.RiskEventDto;
import com.example.batteryrisk.service.MarketPriceService;
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
 * 데이터는 {@code analyses}의 완료 분석(실데이터)이며, 완료 분석이 0건일 때만 결정론적 placeholder로
 * 폴백한다 — 판정 규칙과 폴백 조건은 {@link RiskEventService#riskBoard()} 참고.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
    private final RiskEventService riskEventService;
    private final MarketPriceService marketPriceService;

    public PublicController(RiskEventService riskEventService, MarketPriceService marketPriceService) {
        this.riskEventService = riskEventService;
        this.marketPriceService = marketPriceService;
    }

    @Operation(
            summary = "글로벌 리스크 관제 지도 (비로그인 공개)",
            description = "지도 마커용 공개 subset. 자재·등급·신뢰도·국가·좌표만 포함하며 ERP 내부 상세는 제외한다.")
    @GetMapping("/risk-board")
    public ApiResponse<List<RiskEventDto.RiskBoardItem>> riskBoard() {
        return ApiResponse.ok(riskEventService.riskBoard());
    }

    @Operation(
            summary = "AI 기반 권고 조치 리스트 (비로그인 공개)",
            description = "risk-board와 같은 분석 집합에서 파생하므로 지도와 항상 같은 자재·등급을 가리킨다. "
                    + "권고 문구에 공급사명·재고일수 등 ERP 내부 상세는 포함하지 않는다.")
    @GetMapping("/recommendations")
    public ApiResponse<List<RiskEventDto.AiRecommendationItem>> recommendations() {
        return ApiResponse.ok(riskEventService.aiRecommendations());
    }

    @Operation(
            summary = "실시간 뉴스 속보 (비로그인 공개)",
            description = "수집 원본(raw_events)의 최신 뉴스를 반환한다. 분석(F3)이 붙지 않은 뉴스도 포함하므로 "
                    + "LLM 호출 없이 수집만으로도 채워진다.")
    @GetMapping("/news-feed")
    public ApiResponse<List<RiskEventDto.NewsFeedItem>> newsFeed() {
        return ApiResponse.ok(riskEventService.newsFeed());
    }

    @Operation(
            summary = "원자재 가격 추이 (비로그인 공개)",
            description = "자재별 대표 종목 주가를 프록시로 한 최근 30일 지수(구간 첫 거래일=100). "
                    + "매일 07:00(KST)에 자동 갱신된다.")
    @GetMapping("/price-trends")
    public ApiResponse<List<MarketPriceDto.PriceSeries>> priceTrends() {
        return ApiResponse.ok(marketPriceService.priceTrends());
    }

    @Operation(
            summary = "원자재 가격 요약 카드 (비로그인 공개)",
            description = "가격 추이와 같은 구간·같은 데이터에서 파생한다. risk_score는 연율화 변동성(%)이다.")
    @GetMapping("/price-summaries")
    public ApiResponse<List<MarketPriceDto.PriceSummary>> priceSummaries() {
        return ApiResponse.ok(marketPriceService.priceSummaries());
    }
}

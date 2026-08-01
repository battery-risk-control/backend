package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ExchangeRateDto;
import com.example.batteryrisk.dto.ImportDependencyDto;
import com.example.batteryrisk.dto.MarketPriceDto;
import com.example.batteryrisk.dto.RiskEventDto;
import com.example.batteryrisk.service.ExchangeRateService;
import com.example.batteryrisk.service.ImportDependencyService;
import com.example.batteryrisk.service.MarketPriceService;
import com.example.batteryrisk.service.RiskEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ExchangeRateService exchangeRateService;
    private final ImportDependencyService importDependencyService;

    public PublicController(
            RiskEventService riskEventService,
            MarketPriceService marketPriceService,
            ExchangeRateService exchangeRateService,
            ImportDependencyService importDependencyService) {
        this.riskEventService = riskEventService;
        this.marketPriceService = marketPriceService;
        this.exchangeRateService = exchangeRateService;
        this.importDependencyService = importDependencyService;
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
                    + "LLM 호출 없이 수집만으로도 채워진다. 한 줄 마퀴와 뉴스 목록이 같은 응답을 쓰며 "
                    + "limit으로 노출 건수만 조절한다. country를 주면 지도 마커 클릭에 맞춰 그 국가로 좁힌다 "
                    + "— 이때는 placeholder로 폴백하지 않고 빈 배열을 반환한다.")
    @GetMapping("/news-feed")
    public ApiResponse<List<RiskEventDto.NewsFeedItem>> newsFeed(
            @Parameter(description = "ISO 3166-1 alpha-2 국가 코드. 생략하면 전체", example = "ID")
            @RequestParam(required = false) String country,
            @Parameter(description = "노출 건수(1~100). 마퀴는 작게, 목록은 크게 준다.", example = "20")
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(riskEventService.newsFeed(country, limit));
    }

    @Operation(
            summary = "원자재 가격 추이 (비로그인 공개)",
            description = "자재별 대표 종목 주가를 프록시로 한 지수(구간 첫 거래일=100). 매일 07:00(KST) 자동 갱신. "
                    + "저장값은 프록시 종목의 주가(달러/주)이지 자재의 톤당 가격이 아니라서 절대값에는 의미가 없고 "
                    + "'구간 시작 대비 몇 %'만 뜻이 있다 — 그래서 days를 바꾸면 기준일(base_date)도 함께 옮겨가 "
                    + "지수가 100에서 다시 시작한다.")
    @GetMapping("/price-trends")
    public ApiResponse<List<MarketPriceDto.PriceSeries>> priceTrends(
            @Parameter(description = "조회 구간(일, 1~180). 범위를 벗어나면 잘라서 처리한다.", example = "7")
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(marketPriceService.priceTrends(days));
    }

    @Operation(
            summary = "원자재 가격 요약 카드 (비로그인 공개)",
            description = "가격 추이와 같은 구간·같은 데이터에서 파생한다. risk_score는 연율화 변동성(%)이다. "
                    + "차트와 카드가 어긋나지 않으려면 price-trends와 **같은 days**를 넘겨야 한다.")
    @GetMapping("/price-summaries")
    public ApiResponse<List<MarketPriceDto.PriceSummary>> priceSummaries(
            @Parameter(description = "조회 구간(일, 1~180). price-trends와 같은 값을 넘길 것.", example = "7")
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(marketPriceService.priceSummaries(days));
    }

    @Operation(
            summary = "수입 의존도 (비로그인 공개)",
            description = "공급사 국적별 발주 금액 구성비. 주문마다 결제통화(USD/EUR/KRW)가 달라 "
                    + "고시환율로 원화 환산한 뒤 합산한다(base_date가 환산에 쓴 고시일). "
                    + "**모수가 둘이다** — breakdown은 수입분 안에서의 국가별 비중(합 100%)이고, "
                    + "total은 전체 발주 중 수입이 차지하는 비중(도넛 가운데 숫자)이다. "
                    + "공급사명·절대금액은 포함하지 않는다.")
    @GetMapping("/import-dependency")
    public ApiResponse<ImportDependencyDto.Board> importDependency() {
        return ApiResponse.ok(importDependencyService.importDependency());
    }

    @Operation(
            summary = "환율 (비로그인 공개)",
            description = "한국수출입은행 고시환율(매매기준율)과 직전 고시일 대비 등락. 뉴스 마퀴 옆 밴드용이다. "
                    + "원천이 영업일 11시 전후에 하루 한 번만 갱신되는 일환율이라, rate_date는 요청일이 아니라 "
                    + "실제 고시일이다 — 주말·공휴일에는 직전 영업일 날짜가 내려간다. 등락도 '어제'가 아니라 "
                    + "그 통화의 직전 고시일과 비교한 값이다. 응답은 DB에 적재된 값이라 외부 API 지연과 무관하다.")
    @GetMapping("/exchange-rates")
    public ApiResponse<ExchangeRateDto.ExchangeRateBoard> exchangeRates() {
        return ApiResponse.ok(exchangeRateService.exchangeRates());
    }
}

package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 원자재 가격 추이 관련 DTO. 조회 응답은 프론트 {@code MaterialPriceSeries}/{@code MaterialPriceSummary}
 * 계약을 그대로 반영하고, FastAPI 연동용 요청·응답은 {@code app/schemas/market.py}와 1:1이다.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE이므로 카멜케이스 필드는 {@code price_index} 등으로 직렬화된다.
 */
public final class MarketPriceDto {
    private MarketPriceDto() {}

    // ── 프론트 공개 계약 ────────────────────────────────────────────────

    public record PricePoint(
            @Schema(example = "2026-07-29") String date,
            @Schema(example = "103.4", description = "구간 첫 거래일=100 기준 지수") double priceIndex,

            /**
             * 이 행을 마지막으로 적재한 시각.
             *
             * <p>{@code date}는 <b>거래일</b>이라 날짜뿐이다(일봉). 화면이 "언제 갱신된 값인가"를
             * 표시하려면 시각이 필요한데, 거래일에 00:00을 붙이면 실제 수집 시각이 아닌 값을
             * 지어내는 셈이라 이 컬럼을 그대로 내려보낸다.
             */
            @Schema(example = "2026-08-03T05:15:32Z") java.time.Instant updatedAt
    ) {}

    /**
     * 자재 한 종의 시계열.
     *
     * <p>{@code baseDate}는 지수 100의 기준이 된 거래일이다 — 조회 구간에 따라 달라지므로
     * ({@code days=7}이면 7일 전, {@code days=30}이면 30일 전) 화면이 "무엇 대비 몇 %"인지
     * 표기하려면 이 값이 필요하다. 구간에 데이터가 없으면 null이다.
     *
     * <p>단위 문자열에 날짜를 섞지 않고 필드를 나눈 이유: 화면마다 날짜 표기가 다르고
     * (목업은 {@code 07/25}, 상세는 {@code 2026-07-25}), 단위와 기준일은 서로 다른 정보다.
     */
    public record PriceSeries(
            @Schema(example = "리튬") String material,
            @Schema(example = "지수(기준일=100)") String unit,
            @Schema(example = "2026-07-25", description = "지수 100의 기준 거래일") String baseDate,
            @Schema(description = "이 자재를 조달하는 국가 목록. 화면의 '국가·지역' 필터가 쓴다.")
            List<SourcingCountry> countries,
            List<PricePoint> points
    ) {}

    /**
     * 자재를 조달하는 국가. 가격 자체는 국가별로 나뉘지 않는다 — 자재당 시계열이 하나뿐이고
     * 그 값도 대표 기업 주가 프록시라 채굴국과 연결되지 않는다(글렌코어는 스위스 기업이지만
     * 코발트는 콩고에서 캔다). 그래서 이 목록은 <b>"국가를 고르면 어느 자재 선을 남길지"</b>를
     * 정하는 용도이지, 국가별 가격을 뜻하지 않는다.
     *
     * <p>공급사명·금액·비중은 담지 않는다. 공개 화면이라 조달국까지만 노출한다.
     */
    public record SourcingCountry(
            @Schema(example = "CL") String countryCode,
            @Schema(example = "칠레") String countryName
    ) {}

    /**
     * 요약 카드. risk_score/grade는 <b>가격 변동성 기반</b>이다 — 자재별 리스크 판정을 별도로
     * 정의하기 전까지, 표준 지표인 연율화 변동성(일간 변동성 × √252)을 백분율로 쓴다.
     */
    public record PriceSummary(
            String material,
            @Schema(example = "▲ 1.7%", description = "구간 첫 거래일 대비 등락") String changeLabel,
            @Schema(example = "72", description = "연율화 변동성(%) 0~100") int riskScore,
            @Schema(example = "주의", description = "심각/주의/정상") String grade
    ) {}

    // ── FastAPI 연동 ──────────────────────────────────────────────────
    // fastApiRestClient는 전역 SNAKE_CASE 설정을 쓰지 않으므로, FastAPI와 주고받는 필드는
    // AnalysisDto의 FastApi* 레코드와 마찬가지로 @JsonProperty를 하나하나 명시한다.
    // (생략하면 역직렬화가 조용히 null을 채워 넣어 매핑 단계에서 NPE로 드러난다.)

    public record FastApiPriceRequest(int days) {}

    public record FastApiPricePoint(
            @JsonProperty("material_category") String materialCategory,
            @JsonProperty("ticker") String ticker,
            @JsonProperty("price_date") String priceDate,
            @JsonProperty("close_price") double closePrice,
            @JsonProperty("stock_vol_20d") Double stockVol20d
    ) {}

    public record FastApiPriceResult(
            @JsonProperty("points") List<FastApiPricePoint> points,
            @JsonProperty("failed_tickers") List<String> failedTickers,
            @JsonProperty("mock") boolean mock
    ) {}

    public record FastApiPriceResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("data") FastApiPriceResult data
    ) {}

    /** 수동 갱신 트리거 응답. */
    public record RefreshResult(
            int savedPoints, List<String> failedTickers, String status, String errorMessage) {}
}

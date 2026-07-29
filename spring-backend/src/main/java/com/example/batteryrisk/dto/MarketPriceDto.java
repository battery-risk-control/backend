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
            @Schema(example = "103.4", description = "구간 첫 거래일=100 기준 지수") double priceIndex
    ) {}

    public record PriceSeries(
            @Schema(example = "리튬") String material,
            @Schema(example = "지수(기준일=100)") String unit,
            List<PricePoint> points
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

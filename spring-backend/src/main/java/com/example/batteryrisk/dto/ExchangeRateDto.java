package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 환율(공개 대시보드 ② 밴드) 관련 DTO. 조회 응답은 프론트 계약이고, {@code KoreaEximRate}는
 * 한국수출입은행 현재환율 API 응답과 1:1이다.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE이므로 카멜케이스 필드는 {@code currency_code} 등으로 직렬화된다.
 */
public final class ExchangeRateDto {
    private ExchangeRateDto() {}

    // ── 프론트 공개 계약 ────────────────────────────────────────────────

    /**
     * 통화 한 줄. 마퀴/교대 표기가 별도 계산 없이 그대로 렌더할 수 있도록 표기용 문자열
     * ({@code label}·{@code changeLabel})까지 서버에서 만들어 내려준다.
     */
    public record ExchangeRateItem(
            @Schema(example = "USD") String currencyCode,
            @Schema(example = "미국 달러", description = "API 원문 표기(cur_nm)") String currencyName,
            @Schema(example = "1", description = "고시 단위. JPY·IDR은 100단위 고시라 100이다.")
            int unitMultiplier,
            @Schema(example = "USD/KRW", description = "100단위 통화는 JPY(100)/KRW 형태") String label,
            @Schema(example = "1066.9", description = "매매기준율. unit_multiplier 단위 기준 원문 값")
            double rate,
            @Schema(example = "2.3", description = "직전 고시일 대비 등락(원). 비교 대상이 없으면 null")
            Double changeAmount,
            @Schema(example = "0.22", description = "직전 고시일 대비 등락률(%). 비교 대상이 없으면 null")
            Double changeRate,
            @Schema(example = "▲ 0.22%", description = "비교 대상이 없으면 \"—\"") String changeLabel,
            @Schema(example = "KOREAEXIM",
                    description = "KOREAEXIM=수출입은행 직접 고시, CROSS_USD=USD 경유 재정환율")
            String rateSource,
            @Schema(example = "false",
                    description = "재정환율이면 true. 두 소스의 스냅샷 시각이 섞여 있어 표시용으로만 쓴다"
                            + "(정산·회계 근거로는 불가). 화면에 근사값 표시를 다는 근거로 쓰라고 내려준다.")
            boolean crossRate
    ) {}

    /**
     * 환율 밴드 전체. {@code rateDate}는 <b>요청일이 아니라 실제 고시일</b>이다 — 원천 API가
     * 영업일 11시 전후로만 갱신되므로 주말·공휴일에는 직전 영업일 날짜가 내려간다.
     * 프론트는 이 값을 그대로 노출해 "언제 기준 환율인지"를 사용자가 알 수 있게 한다.
     */
    public record ExchangeRateBoard(
            @Schema(example = "2026-07-31", description = "실제 고시일. 데이터가 없으면 null")
            String rateDate,
            @Schema(example = "KRW") String baseCurrency,
            List<ExchangeRateItem> rates
    ) {}

    // ── 한국수출입은행 현재환율 API 연동 ──────────────────────────────────
    // koreaEximRestClient는 전역 SNAKE_CASE 설정을 쓰지 않고, 원문 키가 소문자 스네이크라
    // @JsonProperty를 하나하나 명시한다. 쓰지 않는 필드(kftc_*, *_efee_r)는 받지 않으므로
    // ignoreUnknown으로 흘려보낸다 — 원천이 필드를 추가해도 파싱이 깨지지 않는다.

    /**
     * 원문 한 줄. 금액이 {@code String}인 이유는 API가 {@code "1,056.23"}처럼 천 단위 콤마를
     * 넣어 내려주기 때문이다 — 숫자 타입으로 받으면 파싱이 깨진다. 콤마 제거는 서비스가 한다.
     *
     * <p>{@code result}는 조회 결과 코드다: 1 성공, 2 DATA코드 오류, 3 인증코드 오류,
     * 4 일일제한횟수(1000회) 마감.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KoreaEximRate(
            @JsonProperty("result") Integer result,
            @JsonProperty("cur_unit") String curUnit,
            @JsonProperty("cur_nm") String curNm,
            @JsonProperty("ttb") String ttb,
            @JsonProperty("tts") String tts,
            @JsonProperty("deal_bas_r") String dealBasR
    ) {}

    // ── 재정환율 소스(ExchangeRate-API open access) 연동 ────────────────

    /**
     * USD 기준 환율 응답. {@code rates}는 "1 USD = N 통화" 형태라 {@code rates.CLP = 933.41}은
     * 1달러가 933.41 칠레 페소라는 뜻이다 — 우리가 원하는 CLP/KRW와는 <b>분자·분모가 반대</b>이므로
     * 나눗셈 방향을 뒤집으면 조용히 역수가 나온다.
     *
     * <p>{@code result}는 {@code "success"} 또는 {@code "error"}다(수출입은행의 숫자 코드와 다르다).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrossRateResponse(
            @JsonProperty("result") String result,
            @JsonProperty("base_code") String baseCode,
            @JsonProperty("time_last_update_utc") String lastUpdateUtc,
            @JsonProperty("rates") Map<String, Double> rates
    ) {}

    /** 수집 결과(로그·기동 훅용). 공개 API로는 노출하지 않는다. */
    public record RefreshResult(
            int savedRates, List<String> savedDates, String status, String errorMessage) {}
}

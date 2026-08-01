package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 통화별 일별 고시환율(한국수출입은행 현재환율 API 원문). 공개 대시보드 "환율정보" 밴드의 원천이다.
 *
 * <p>{@code dealBaseRate}·{@code ttb}·{@code tts}는 {@link #getUnitMultiplier()} 단위 기준의
 * 원문 값이다 — JPY/IDR은 100단위 고시라 951.05는 "100엔당 951.05원"을 뜻한다. 1단위로 환산하지
 * 않는 이유는 환율 표기 관례를 화면과 맞추기 위해서다.
 *
 * <p>(currencyCode, rateDate) 복합키라 같은 고시일을 다시 받아도 갱신으로 수렴한다 —
 * 스케줄러가 하루 두 번 돌고 기동 시 backfill까지 하지만 중복 행이 생기지 않는다.
 */
@Entity
@Table(name = "exchange_rate_points")
@IdClass(ExchangeRatePoint.Key.class)
public class ExchangeRatePoint {

    /** 복합키. JPA 규약상 no-arg 생성자·equals/hashCode가 필요하다. */
    public static class Key implements Serializable {
        private String currencyCode;
        private LocalDate rateDate;

        protected Key() {}

        public Key(String currencyCode, LocalDate rateDate) {
            this.currencyCode = currencyCode;
            this.rateDate = rateDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(currencyCode, key.currencyCode)
                    && Objects.equals(rateDate, key.rateDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currencyCode, rateDate);
        }
    }

    @Id
    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode;

    @Id
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "currency_name", nullable = false, length = 60)
    private String currencyName;

    @Column(name = "unit_multiplier", nullable = false)
    private int unitMultiplier;

    @Column(name = "deal_base_rate", nullable = false)
    private double dealBaseRate;

    @Column(name = "ttb")
    private Double ttb;

    @Column(name = "tts")
    private Double tts;

    /** {@code KOREAEXIM}(직접 고시) 또는 {@code CROSS_USD}(USD 경유 재정환율). */
    @Column(name = "rate_source", nullable = false, length = 20)
    private String rateSource;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExchangeRatePoint() {}

    /** 수출입은행이 직접 고시한 환율. */
    public static ExchangeRatePoint direct(
            String currencyCode, LocalDate rateDate, String currencyName,
            int unitMultiplier, double dealBaseRate, Double ttb, Double tts) {
        return of(currencyCode, rateDate, currencyName, unitMultiplier,
                dealBaseRate, ttb, tts, SOURCE_KOREAEXIM);
    }

    /**
     * USD를 경유해 계산한 재정환율. 수출입은행이 고시하지 않는 조달국 통화용이다.
     * 전신환 송금값(ttb·tts)은 원천에 없으므로 채우지 않는다 — 매매기준율만 성립한다.
     */
    public static ExchangeRatePoint cross(
            String currencyCode, LocalDate rateDate, String currencyName,
            int unitMultiplier, double dealBaseRate) {
        return of(currencyCode, rateDate, currencyName, unitMultiplier,
                dealBaseRate, null, null, SOURCE_CROSS_USD);
    }

    private static ExchangeRatePoint of(
            String currencyCode, LocalDate rateDate, String currencyName,
            int unitMultiplier, double dealBaseRate, Double ttb, Double tts, String rateSource) {
        ExchangeRatePoint point = new ExchangeRatePoint();
        point.currencyCode = currencyCode;
        point.rateDate = rateDate;
        point.currencyName = currencyName;
        point.unitMultiplier = unitMultiplier;
        point.dealBaseRate = dealBaseRate;
        point.ttb = ttb;
        point.tts = tts;
        point.rateSource = rateSource;
        point.updatedAt = Instant.now();
        return point;
    }

    public static final String SOURCE_KOREAEXIM = "KOREAEXIM";
    public static final String SOURCE_CROSS_USD = "CROSS_USD";

    public String getCurrencyCode() { return currencyCode; }
    public LocalDate getRateDate() { return rateDate; }
    public String getCurrencyName() { return currencyName; }
    public int getUnitMultiplier() { return unitMultiplier; }
    public double getDealBaseRate() { return dealBaseRate; }
    public Double getTtb() { return ttb; }
    public Double getTts() { return tts; }
    public String getRateSource() { return rateSource; }
    public Instant getUpdatedAt() { return updatedAt; }
}

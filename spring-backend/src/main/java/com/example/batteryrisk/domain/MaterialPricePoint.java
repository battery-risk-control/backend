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
 * 자재 대분류별 대표 종목의 일별 종가(가격 프록시). 공개 대시보드 "원자재 가격 추이" 패널의 원천이다.
 *
 * <p>(materialCategory, priceDate) 복합키라 같은 구간을 다시 수집해도 갱신으로 수렴한다 —
 * MarketPriceService가 매번 넉넉한 구간을 요청하므로, 스케줄러가 며칠 쉬어도 빈 구간이 메워진다.
 */
@Entity
@Table(name = "material_price_points")
@IdClass(MaterialPricePoint.Key.class)
public class MaterialPricePoint {

    /** 복합키. JPA 규약상 no-arg 생성자·equals/hashCode가 필요하다. */
    public static class Key implements Serializable {
        private String materialCategory;
        private LocalDate priceDate;

        protected Key() {}

        public Key(String materialCategory, LocalDate priceDate) {
            this.materialCategory = materialCategory;
            this.priceDate = priceDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(materialCategory, key.materialCategory)
                    && Objects.equals(priceDate, key.priceDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(materialCategory, priceDate);
        }
    }

    @Id
    @Column(name = "material_category", nullable = false, length = 40)
    private String materialCategory;

    @Id
    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "close_price", nullable = false)
    private double closePrice;

    @Column(name = "stock_vol_20d")
    private Double stockVol20d;

    @Column(name = "ticker", length = 20)
    private String ticker;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MaterialPricePoint() {}

    public static MaterialPricePoint of(
            String materialCategory, LocalDate priceDate, double closePrice,
            Double stockVol20d, String ticker) {
        MaterialPricePoint point = new MaterialPricePoint();
        point.materialCategory = materialCategory;
        point.priceDate = priceDate;
        point.closePrice = closePrice;
        point.stockVol20d = stockVol20d;
        point.ticker = ticker;
        point.updatedAt = Instant.now();
        return point;
    }

    /**
     * 20일 변동성을 보존용으로만 덮어쓴다. 장중 갱신(5일 창)은 20일 변동성을 계산할 수 없어 전부
     * null로 오는데, 그 null이 매일 07:00에 계산해 둔 최근 변동성을 지워 리스크가 0으로 죽었다.
     * MarketPriceService.refresh가 "새 값이 null이면 기존 값을 유지"하려고 이 setter를 쓴다.
     */
    public void setStockVol20d(Double stockVol20d) { this.stockVol20d = stockVol20d; }

    public String getMaterialCategory() { return materialCategory; }
    public LocalDate getPriceDate() { return priceDate; }
    public double getClosePrice() { return closePrice; }
    public Double getStockVol20d() { return stockVol20d; }
    public String getTicker() { return ticker; }
    public Instant getUpdatedAt() { return updatedAt; }
}

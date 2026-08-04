package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 수입 의존도(공개 대시보드 ④ 도넛) DTO. 프론트 {@code ImportDependencyData} 계약을 그대로 반영한다.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE이므로 카멜케이스 필드는 {@code base_date} 등으로 직렬화된다.
 */
public final class ImportDependencyDto {
    private ImportDependencyDto() {}

    /**
     * 도넛 조각 하나 = 수입국 하나.
     *
     * <p><b>색은 담지 않는다.</b> 프론트 mock에는 색이 들어 있었지만 배색은 화면의 몫이고,
     * 백엔드가 정하면 디자인 토큰이 바뀔 때 서버를 고쳐야 한다.
     */
    public record CountryShare(
            @Schema(example = "칠레", description = "한글 국가명. 지도와 같은 표기") String label,
            @Schema(example = "25.3", description = "수입분 합계 대비 비중(%). 조각 합이 100") double value,
            @Schema(example = "CL") String countryCode
    ) {}

    /**
     * 도넛 전체.
     *
     * <p><b>모수가 둘로 나뉜다</b> — 목업에서 조각 합은 100인데 가운데는 82.3%라 서로 다른 기준이다.
     * <ul>
     *   <li>{@code breakdown} — <b>수입분 안에서의</b> 국가별 구성비(합 100%). 국내(KR)는 빠진다.</li>
     *   <li>{@code total} — <b>전체 발주 중</b> 수입이 차지하는 비중(%). 가운데 숫자.</li>
     * </ul>
     *
     * <p>금액은 {@code baseDate} 시점의 고시환율로 원화 환산한 값이다. 발주 시점 환율이 아니라
     * 단일 기준환율을 쓰는 이유는 이 화면이 <b>조달 구조</b>를 보여주기 때문이다 — 발주 시점
     * 환율을 쓰면 같은 물량을 사도 환율이 흔들려 비중이 바뀐다.
     */
    public record Board(
            @Schema(example = "86.0", description = "전체 발주 중 수입 비중(%). 도넛 가운데 숫자")
            double total,
            @Schema(example = "2026", description = "집계 대상 발주 기간. 화면 제목에 붙는다")
            String year,
            @Schema(example = "2026-07-31", description = "원화 환산에 쓴 환율 고시일")
            String baseDate,
            List<CountryShare> breakdown
    ) {}
}

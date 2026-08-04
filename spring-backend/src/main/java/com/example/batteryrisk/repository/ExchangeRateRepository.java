package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.ExchangeRatePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExchangeRateRepository
        extends JpaRepository<ExchangeRatePoint, ExchangeRatePoint.Key> {

    /**
     * 공개 환율 밴드용 구간 조회. 통화·날짜 오름차순이라 서비스에서 그대로 통화별로 묶고
     * 마지막 두 점(최근 고시일·직전 고시일)으로 전일 대비를 계산할 수 있다.
     *
     * <p>"최근 2개 고시일"을 SQL로 직접 집는 대신 넉넉한 날짜 구간을 긁어오는 이유: 고시일은
     * 주말·공휴일로 끊겨서 "어제"가 곧 "직전 고시일"이 아니고, 통화마다 결측일이 다를 수 있다.
     * 구간이 통화당 수 행 규모라 애플리케이션에서 접는 편이 단순하고 안전하다.
     */
    List<ExchangeRatePoint> findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(
            LocalDate from);
}

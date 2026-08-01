package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ImportDependencyDto;
import com.example.batteryrisk.repository.ErpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수입 의존도(공개 대시보드 ④ 도넛). 발주 금액을 공급사 국적별로 묶어 구성비를 만든다.
 *
 * <p><b>가격 추이와 달리 FastAPI를 거치지 않는다</b> — 원천이 외부 시세가 아니라 우리 ERP
 * (purchase_orders · purchase_order_items · suppliers)라 Spring 안에서 끝난다. 새 테이블도
 * 필요 없고 기존 테이블 조인 하나면 된다.
 *
 * <p><b>통화 혼재가 이 기능의 핵심 함정이다.</b> {@code purchase_orders.currency}가 주문마다
 * USD/EUR/KRW로 달라, 환산 없이 {@code ordered_quantity × unit_price}를 합치면 서로 다른 통화를
 * 더한 값이 나온다. 실측으로도 환산 여부에 따라 칠레 비중이 17.6% ↔ 21.8%로 갈렸다 — 예외도
 * 로그도 남지 않아 조용히 틀리는 종류다.
 */
@Service
public class ImportDependencyService {
    private static final Logger log = LoggerFactory.getLogger(ImportDependencyService.class);

    /** 국내 조달로 보는 국가. 도넛 조각에서는 빠지고 "전체 중 수입 비중" 계산의 분모에만 들어간다. */
    private static final String DOMESTIC_COUNTRY = "KR";

    private final ErpRepository erpRepository;
    private final ExchangeRateService exchangeRateService;

    public ImportDependencyService(
            ErpRepository erpRepository, ExchangeRateService exchangeRateService) {
        this.erpRepository = erpRepository;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * 국가별 수입 의존도. 환율이 아직 수집되지 않았으면 빈 도넛을 반환한다 — 환산 없이 합치면
     * 조용히 틀린 비중이 나오므로, 틀린 숫자보다 빈 화면이 낫다.
     */
    public ImportDependencyDto.Board importDependency() {
        List<ErpRepository.CountryPurchaseAmountRow> rows =
                erpRepository.aggregatePurchaseAmountsByCountry();
        if (rows.isEmpty()) {
            return new ImportDependencyDto.Board(0, null, null, List.of());
        }

        Map<String, Double> rates = exchangeRateService.latestRatesToKrw();
        LocalDate rateDate = exchangeRateService.latestRateDate();

        Map<String, Double> amountByCountry = new LinkedHashMap<>();
        double total = 0;
        for (ErpRepository.CountryPurchaseAmountRow row : rows) {
            Double rate = rates.get(row.currency());
            if (rate == null) {
                // 환산할 수 없는 통화는 버린다. 1.0으로 대충 넣으면 USD 발주가 원화 금액으로
                // 둔갑해 비중이 1400배 축소되는데, 화면에는 그럴듯한 숫자로 나온다.
                log.warn("수입 의존도: {} 환율이 없어 {} 발주를 제외합니다.", row.currency(), row.countryCode());
                continue;
            }
            double krw = row.amount().doubleValue() * rate;
            amountByCountry.merge(row.countryCode(), krw, Double::sum);
            total += krw;
        }
        if (total == 0) {
            log.warn("수입 의존도: 환산 가능한 발주가 없습니다(환율 수집 여부 확인 필요).");
            return new ImportDependencyDto.Board(0, periodLabel(rows), null, List.of());
        }

        double domestic = amountByCountry.getOrDefault(DOMESTIC_COUNTRY, 0.0);
        double imported = total - domestic;

        // 조각은 "수입분 안에서의" 구성비라 분모가 수입 합계다. 전체 합계로 나누면 조각 합이
        // 100이 아니라 86이 되어 가운데 숫자와 겹쳐 보인다(목업이 둘을 다른 기준으로 그린 이유).
        List<ImportDependencyDto.CountryShare> breakdown = new ArrayList<>();
        if (imported > 0) {
            for (Map.Entry<String, Double> entry : amountByCountry.entrySet()) {
                if (DOMESTIC_COUNTRY.equals(entry.getKey())) {
                    continue;
                }
                breakdown.add(new ImportDependencyDto.CountryShare(
                        RiskEventService.countryNameKo(entry.getKey()),
                        round(entry.getValue() / imported * 100.0),
                        entry.getKey()));
            }
            breakdown.sort(Comparator.comparingDouble(
                    ImportDependencyDto.CountryShare::value).reversed());
        }

        return new ImportDependencyDto.Board(
                round(imported / total * 100.0),
                periodLabel(rows),
                rateDate == null ? null : rateDate.toString(),
                breakdown);
    }

    /**
     * 화면 제목에 붙는 기간. 한 해 안이면 연도만, 걸쳐 있으면 범위로 적는다 —
     * "2026" 하나로 뭉뚱그리면 실제로는 2월~7월치인데 한 해 전체로 오독된다.
     */
    private static String periodLabel(List<ErpRepository.CountryPurchaseAmountRow> rows) {
        int from = rows.stream().map(ErpRepository.CountryPurchaseAmountRow::firstOrderDate)
                .min(LocalDate::compareTo).map(LocalDate::getYear).orElse(0);
        int to = rows.stream().map(ErpRepository.CountryPurchaseAmountRow::lastOrderDate)
                .max(LocalDate::compareTo).map(LocalDate::getYear).orElse(0);
        return from == to ? String.valueOf(from) : from + "~" + to;
    }

    /** 소수 1자리. 도넛 조각 라벨이 "25.3%"로 표기되므로 그 이상은 화면에서 버려진다. */
    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

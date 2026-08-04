package com.example.batteryrisk;

import com.example.batteryrisk.dto.ImportDependencyDto;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.service.ExchangeRateService;
import com.example.batteryrisk.service.ImportDependencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수입 의존도의 <b>통화 환산</b>과 <b>두 모수</b>를 지키는 회귀 테스트.
 *
 * <p>둘 다 조용히 틀리는 종류다 — 환산을 빠뜨려도, 도넛 조각을 전체 합계로 나눠도 그럴듯한
 * 백분율이 나오고 예외나 로그가 남지 않는다. 실측으로도 환산 여부에 따라 칠레 비중이
 * 17.6% ↔ 21.8%로 갈렸다.
 */
class ImportDependencyServiceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-02-13");
    private static final LocalDate TO = LocalDate.parse("2026-07-12");

    private ErpRepository erpRepository;
    private ExchangeRateService exchangeRateService;
    private ImportDependencyService service;

    @BeforeEach
    void setUp() {
        erpRepository = mock(ErpRepository.class);
        exchangeRateService = mock(ExchangeRateService.class);
        when(exchangeRateService.latestRatesToKrw())
                .thenReturn(Map.of("KRW", 1.0, "USD", 1441.1, "EUR", 1661.16));
        when(exchangeRateService.latestRateDate()).thenReturn(LocalDate.parse("2026-07-31"));
        service = new ImportDependencyService(erpRepository, exchangeRateService);
    }

    /**
     * 환산을 빠뜨리면 통화가 다른 금액을 그대로 더하게 된다. 여기서는 같은 액면 1,000이지만
     * 원화 환산 후에는 USD가 EUR보다 작아야 한다(1441.1 &lt; 1661.16).
     */
    @Test
    void convertsEachCurrencyBeforeSummingShares() {
        stub(
                row("CL", "USD", 1000),   // 1,441,100 원
                row("AU", "EUR", 1000));  // 1,661,160 원

        List<ImportDependencyDto.CountryShare> breakdown = service.importDependency().breakdown();

        // 액면이 같다고 50:50이 되면 환산이 빠진 것이다.
        assertThat(breakdown).extracting(ImportDependencyDto.CountryShare::label)
                .containsExactly("호주", "칠레");
        assertThat(breakdown.get(0).value())
                .isCloseTo(1661.16 / (1441.1 + 1661.16) * 100, within(0.1));
        assertThat(breakdown.get(1).value())
                .isCloseTo(1441.1 / (1441.1 + 1661.16) * 100, within(0.1));
    }

    /**
     * 모수가 둘이다 — 조각은 <b>수입분</b> 대비(합 100%), 가운데는 <b>전체</b> 대비.
     * 조각까지 전체로 나누면 합이 100이 아니라 total과 같은 값이 되어 도넛이 다 채워지지 않는다.
     */
    @Test
    void breakdownIsShareOfImportsWhileTotalIsShareOfEverything() {
        stub(
                row("KR", "KRW", 2000),   // 국내 2,000원
                row("CL", "KRW", 6000),   // 수입 6,000원
                row("AU", "KRW", 2000));  // 수입 2,000원

        ImportDependencyDto.Board board = service.importDependency();

        // 전체 10,000 중 수입 8,000 -> 80%
        assertThat(board.total()).isEqualTo(80.0);
        // 조각은 수입 8,000 기준이라 75% + 25% = 100%
        assertThat(board.breakdown()).extracting(ImportDependencyDto.CountryShare::value)
                .containsExactly(75.0, 25.0);
        assertThat(board.breakdown()).extracting(ImportDependencyDto.CountryShare::label)
                .containsExactly("칠레", "호주");
    }

    /** 국내(KR)는 조각에서 빠진다 — "수입" 의존도라 국내가 조각에 끼면 이름과 내용이 어긋난다. */
    @Test
    void domesticSourcingIsExcludedFromDonutSlices() {
        stub(row("KR", "KRW", 5000), row("CL", "KRW", 5000));

        ImportDependencyDto.Board board = service.importDependency();

        assertThat(board.breakdown()).extracting(ImportDependencyDto.CountryShare::countryCode)
                .containsExactly("CL");
        assertThat(board.total()).isEqualTo(50.0);
    }

    /**
     * 환율이 없는 통화는 1.0으로 때우지 않고 제외한다. 1.0을 넣으면 USD 발주가 원화 금액으로
     * 둔갑해 비중이 1400배 축소되는데, 화면에는 그럴듯한 숫자로 나와 알아챌 수 없다.
     */
    @Test
    void dropsRowsWhoseCurrencyHasNoRateInsteadOfAssumingOne() {
        when(exchangeRateService.latestRatesToKrw()).thenReturn(Map.of("KRW", 1.0));
        stub(row("CL", "USD", 1000), row("AU", "KRW", 1000));

        ImportDependencyDto.Board board = service.importDependency();

        // USD 환율이 없으므로 칠레는 통째로 빠지고 호주만 남는다.
        assertThat(board.breakdown()).extracting(ImportDependencyDto.CountryShare::countryCode)
                .containsExactly("AU");
    }

    /** 환율 수집 전이면 빈 도넛 — 환산 없이 합친 틀린 숫자보다 빈 화면이 낫다. */
    @Test
    void returnsEmptyBoardWhenNothingCanBeConverted() {
        when(exchangeRateService.latestRatesToKrw()).thenReturn(Map.of());
        stub(row("CL", "USD", 1000));

        ImportDependencyDto.Board board = service.importDependency();

        assertThat(board.total()).isEqualTo(0);
        assertThat(board.breakdown()).isEmpty();
    }

    /** 발주가 한 해 안이면 연도만, 걸쳐 있으면 범위로 — "2026"이 한 해 전체로 오독되지 않게. */
    @Test
    void labelsThePeriodFromActualOrderDates() {
        stub(row("CL", "KRW", 1000));
        assertThat(service.importDependency().year()).isEqualTo("2026");

        when(erpRepository.aggregatePurchaseAmountsByCountry()).thenReturn(List.of(
                new ErpRepository.CountryPurchaseAmountRow(
                        "CL", "KRW", BigDecimal.valueOf(1000),
                        LocalDate.parse("2025-11-01"), LocalDate.parse("2026-03-01"))));
        assertThat(service.importDependency().year()).isEqualTo("2025~2026");
    }

    private void stub(ErpRepository.CountryPurchaseAmountRow... rows) {
        when(erpRepository.aggregatePurchaseAmountsByCountry()).thenReturn(List.of(rows));
    }

    private static ErpRepository.CountryPurchaseAmountRow row(
            String country, String currency, long amount) {
        return new ErpRepository.CountryPurchaseAmountRow(
                country, currency, BigDecimal.valueOf(amount), FROM, TO);
    }
}

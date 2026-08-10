package com.example.batteryrisk;

import com.example.batteryrisk.domain.MaterialPricePoint;
import com.example.batteryrisk.dto.MarketPriceDto;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.MaterialPriceRepository;
import com.example.batteryrisk.service.MarketPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가격 추이의 <b>구간별 기준일 재산정</b> 회귀 테스트.
 *
 * <p>저장값은 프록시 종목의 주가(달러/주)이지 자재의 톤당 가격이 아니다. 절대값에는 의미가 없고
 * "구간 시작 대비 몇 %"만 뜻이 있으므로, 구간을 바꾸면 기준일도 함께 옮겨 지수가 100에서 다시
 * 시작해야 한다. 기준일을 고정해두면 7일 차트가 105에서 시작하면서도 "기준일=100" 라벨을 달아
 * 화면과 표기가 조용히 어긋난다 — 예외도 로그도 남지 않는 종류라 테스트로 고정한다.
 */
class MarketPriceTrendTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private RestClient restClient;
    private MaterialPriceRepository priceRepository;
    private ErpRepository erpRepository;
    private MarketPriceService service;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        priceRepository = mock(MaterialPriceRepository.class);
        erpRepository = mock(ErpRepository.class);
        when(erpRepository.findMaterialSourcingCountries()).thenReturn(List.of());
        service = new MarketPriceService(restClient, priceRepository, erpRepository);
    }

    /**
     * 같은 데이터라도 구간이 좁아지면 기준일이 그 구간의 첫 거래일로 옮겨간다.
     * 30일 조회에서 107.3이던 마지막 점이, 7일 조회에서는 더 늦은 기준 대비로 다시 계산된다.
     */
    @Test
    void rebasesIndexToTheFirstTradingDayOfTheRequestedWindow() {
        // 100 -> 105 -> 110 으로 오른 사흘치. 구간을 어디서 끊느냐에 따라 기준이 달라진다.
        stubPoints(
                point("COBALT", 10, 100.0),
                point("COBALT", 5, 105.0),
                point("COBALT", 0, 110.0));

        MarketPriceDto.PriceSeries wide = service.priceTrends(30).get(0);

        // 30일 구간: 가장 오래된 점이 기준 -> 100.0에서 시작해 110/100 = 110.0으로 끝난다.
        assertThat(wide.baseDate()).isEqualTo(LocalDate.now(KST).minusDays(10).toString());
        assertThat(wide.points()).extracting(MarketPriceDto.PricePoint::priceIndex)
                .containsExactly(100.0, 105.0, 110.0);
    }

    @Test
    void narrowWindowKeepsTheFixedRefreshWindowBase() {
        stubPoints(
                point("COBALT", 10, 100.0),
                point("COBALT", 5, 105.0),
                point("COBALT", 0, 110.0));
        // 7일 구간이면 리포지토리가 최근 두 점만 돌려준다(from 경계가 뒤로 밀리므로).
        when(priceRepository.findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(
                LocalDate.now(KST).minusDays(7)))
                .thenReturn(List.of(
                        point("COBALT", 5, 105.0),
                        point("COBALT", 0, 110.0)));

        MarketPriceDto.PriceSeries narrow = service.priceTrends(7).get(0);

        // 기준일이 옮겨가 105가 100이 되고, 110은 104.8이 된다(110/105 = 1.0476).
        assertThat(narrow.baseDate()).isEqualTo(LocalDate.now(KST).minusDays(10).toString());
        assertThat(narrow.points()).extracting(MarketPriceDto.PricePoint::priceIndex)
                .containsExactly(105.0, 110.0);
    }

    /**
     * "1일" 탭(days=1)은 일봉이 아니라 FastAPI에서 받은 <b>시간별(1h) 장중 흐름</b>이다. 첫 봉=100으로
     * 지수화하고, 시각은 KST로 환산해 라벨로 담는다. 등락은 첫 봉→마지막 봉으로 계산한다.
     */
    @Test
    void oneDayTabReturnsHourlyIntradayFlowNormalizedToFirstBar() {
        stubIntraday(
                new MarketPriceDto.FastApiPricePoint("COBALT", "GLNCY", "2026-08-07T09:00:00-04:00", 100.0, null),
                new MarketPriceDto.FastApiPricePoint("COBALT", "GLNCY", "2026-08-07T10:00:00-04:00", 105.0, null),
                new MarketPriceDto.FastApiPricePoint("COBALT", "GLNCY", "2026-08-07T11:00:00-04:00", 110.0, null));
        // 리스크 지수는 저장된 일간 변동성에서 온다(시간 단위 정의가 없어) — 여기선 등락만 검증한다.
        stubPoints(point("COBALT", 1, 50.0));

        MarketPriceDto.PriceSeries series = service.priceTrends(1).get(0);

        // 첫 봉=100 기준: 105/100=105.0, 110/100=110.0.
        assertThat(series.points()).extracting(MarketPriceDto.PricePoint::priceIndex)
                .containsExactly(100.0, 105.0, 110.0);
        // 09:00 EDT(-04:00) = 22:00 KST. 라벨은 KST 시각 문자열.
        assertThat(series.baseDate()).isEqualTo("2026-08-07T22:00");
        assertThat(series.points()).extracting(MarketPriceDto.PricePoint::date)
                .containsExactly("2026-08-07T22:00", "2026-08-07T23:00", "2026-08-08T00:00");
        // 등락: 첫 봉(100)→마지막 봉(110) = +10.0%
        assertThat(service.priceSummaries(1).get(0).changeLabel()).isEqualTo("▲ 10.0%");
    }

    /** FastAPI 시간별 응답을 RestClient 체인에 물린다(본문 없는 POST). */
    private void stubIntraday(MarketPriceDto.FastApiPricePoint... points) {
        MarketPriceDto.FastApiPriceResponse response = new MarketPriceDto.FastApiPriceResponse(
                true, new MarketPriceDto.FastApiPriceResult(List.of(points), List.of(), false));
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MarketPriceDto.FastApiPriceResponse.class)).thenReturn(response);
    }

    /** 요청 구간이 실제 조회 경계로 전달되는지 — 여기가 어긋나면 재산정이 조용히 무력화된다. */
    @Test
    void loadsTheFixedRefreshWindowForAStableBase() {
        stubPoints(point("COBALT", 0, 100.0));

        service.priceTrends(7);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(priceRepository)
                .findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.now(KST).minusDays(180));
    }

    /**
     * 범위를 벗어난 값은 400이 아니라 잘라서 처리한다 — 공개 화면이라 파라미터 하나 때문에
     * 패널이 통째로 비면 안 된다(뉴스 속보 limit과 같은 방침).
     */
    @Test
    void usesTheFixedRefreshWindowForOutOfRangeRequests() {
        stubPoints(point("COBALT", 0, 100.0));

        service.priceTrends(0);
        service.priceTrends(9999);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(priceRepository, org.mockito.Mockito.times(2))
                .findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(captor.capture());
        LocalDate today = LocalDate.now(KST);
        assertThat(captor.getAllValues().get(0)).isEqualTo(today.minusDays(180));
        assertThat(captor.getAllValues().get(1)).isEqualTo(today.minusDays(180));
    }

    /** 요약 카드도 같은 구간을 받아야 차트와 어긋나지 않는다. */
    @Test
    void summariesUseTheSameWindowAsTrends() {
        stubPoints(
                point("COBALT", 5, 105.0),
                point("COBALT", 0, 110.0));

        MarketPriceDto.PriceSummary summary = service.priceSummaries(7).get(0);

        // 구간 첫 거래일 대비 등락 — 110/105 = +4.8%
        assertThat(summary.changeLabel()).isEqualTo("▲ 4.8%");
    }

    /**
     * 조달국이 자재별로 묶여 응답에 실리는지. 이 목록이 화면의 "국가·지역" 드롭다운을 만들고
     * 필터링 기준이 되므로, 자재가 섞이면 칠레를 골랐을 때 니켈 선이 남는 식으로 조용히 틀린다.
     */
    @Test
    void attachesSourcingCountriesGroupedByMaterial() {
        when(erpRepository.findMaterialSourcingCountries()).thenReturn(List.of(
                new ErpRepository.MaterialCountryRow("COBALT", "CD"),
                new ErpRepository.MaterialCountryRow("COBALT", "CA"),
                new ErpRepository.MaterialCountryRow("LITHIUM", "CL")));
        stubPoints(point("COBALT", 0, 100.0));

        MarketPriceDto.PriceSeries cobalt = service.priceTrends(7).get(0);

        assertThat(cobalt.countries())
                .extracting(MarketPriceDto.SourcingCountry::countryCode)
                .containsExactly("CD", "CA");
        // 국가명은 지도와 같은 표(RiskEventService.COUNTRIES)에서 온다 — 두 벌이면 같은 나라를
        // 화면마다 다르게 부르게 된다.
        assertThat(cobalt.countries())
                .extracting(MarketPriceDto.SourcingCountry::countryName)
                .containsExactly("콩고민주공화국", "캐나다");
    }

    /** 조달 관계가 없는 자재도 차트에서 빠지면 안 된다 — 빈 목록으로 내려가 "전체"에서는 보인다. */
    @Test
    void materialsWithoutSourcingDataStillAppearWithEmptyCountries() {
        stubPoints(point("RARE_EARTH", 0, 100.0));

        MarketPriceDto.PriceSeries series = service.priceTrends(7).get(0);

        assertThat(series.countries()).isEmpty();
        assertThat(series.points()).hasSize(1);
    }

    private void stubPoints(MaterialPricePoint... points) {
        when(priceRepository.findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(any()))
                .thenReturn(List.of(points));
    }

    private static MaterialPricePoint point(String material, int daysAgo, double close) {
        return MaterialPricePoint.of(
                material, LocalDate.now(KST).minusDays(daysAgo), close, null, "TEST");
    }
}

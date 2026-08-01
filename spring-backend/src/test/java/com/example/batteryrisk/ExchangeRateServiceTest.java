package com.example.batteryrisk;

import com.example.batteryrisk.domain.ExchangeRatePoint;
import com.example.batteryrisk.dto.ExchangeRateDto;
import com.example.batteryrisk.repository.ExchangeRateRepository;
import com.example.batteryrisk.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 환율 수집·조회의 회귀 테스트.
 *
 * <p>한국수출입은행 API는 응답 형태에 함정이 셋 있고, 어느 것도 예외를 던지지 않아 조용히 틀린다.
 * <ul>
 *   <li>금액이 {@code "1,056.23"}처럼 <b>콤마가 박힌 문자열</b>이다. 콤마를 안 떼면 파싱이 깨지고,
 *       숫자 타입으로 받으면 역직렬화 자체가 실패한다.</li>
 *   <li>JPY·IDR은 {@code "JPY(100)"}처럼 <b>100단위 고시</b>다. 단위를 무시하면 엔화가 원화의
 *       951배로 표시된다.</li>
 *   <li>비영업일·11시 이전에는 <b>빈 배열</b>이 돌아온다. 이걸 실패로 처리하면 주말마다 밴드가 빈다.</li>
 * </ul>
 */
class ExchangeRateServiceTest {
    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String BASE_URL = "https://oapi.koreaexim.go.kr";
    private static final String AUTH_KEY = "TEST-AUTH-KEY";

    /**
     * 실제 응답을 줄인 것. 검증 목적으로 다섯 가지를 일부러 섞었다.
     * <ul>
     *   <li>{@code JPY(100)} — 100단위 고시</li>
     *   <li>{@code USD} — 콤마가 박힌 금액</li>
     *   <li>{@code THB} — <b>노출 목록 밖이지만 거래되는 통화. 저장은 돼야 한다</b>
     *       (수집은 전량, 노출만 선택)</li>
     *   <li>{@code DEM}(독일 마르크)·{@code KRW} — 매매기준율은 있으나 ttb·tts가 둘 다 0이다.
     *       폐지 통화와 기준통화가 이 모양이라 "거래되지 않음"으로 걸러져야 한다</li>
     *   <li>{@code AUD}의 매매기준율 {@code "0"} — 거래 통화라도 값이 결손이면 버려야 한다
     *       (화면에 "0원"이 뜨는 것이 값이 없는 것보다 나쁘다)</li>
     * </ul>
     * {@code kftc_*}·{@code *_efee_r}는 우리가 쓰지 않는 필드다 — 그대로 둬서 "모르는 필드는 무시된다"가
     * 함께 검증된다.
     */
    private static final String EXIM_RESPONSE = """
            [
              {"result":1,"cur_unit":"AUD","ttb":"0","tts":"0","deal_bas_r":"0",
               "bkpr":"0","yy_efee_r":"0","ten_dd_efee_r":"0","kftc_bkpr":"0",
               "kftc_deal_bas_r":"0","cur_nm":"호주 달러"},
              {"result":1,"cur_unit":"CNH","ttb":"162.01","tts":"165.28","deal_bas_r":"163.65",
               "bkpr":"163","yy_efee_r":"0","ten_dd_efee_r":"0","kftc_bkpr":"163",
               "kftc_deal_bas_r":"163.65","cur_nm":"위안화"},
              {"result":1,"cur_unit":"DEM","ttb":"0","tts":"0","deal_bas_r":"657.98",
               "bkpr":"0","yy_efee_r":"0","ten_dd_efee_r":"0","kftc_bkpr":"0",
               "kftc_deal_bas_r":"657.98","cur_nm":"독일 마르크"},
              {"result":1,"cur_unit":"JPY(100)","ttb":"941.53","tts":"960.56","deal_bas_r":"951.05",
               "bkpr":"951","yy_efee_r":"0.96833","ten_dd_efee_r":"0.0242","kftc_bkpr":"951",
               "kftc_deal_bas_r":"951.05","cur_nm":"일본 옌"},
              {"result":1,"cur_unit":"KRW","ttb":"0","tts":"0","deal_bas_r":"1",
               "bkpr":"1","yy_efee_r":"0","ten_dd_efee_r":"0","kftc_bkpr":"1",
               "kftc_deal_bas_r":"1","cur_nm":"한국 원"},
              {"result":1,"cur_unit":"THB","ttb":"32.57","tts":"33.22","deal_bas_r":"32.9",
               "bkpr":"32","yy_efee_r":"0","ten_dd_efee_r":"0","kftc_bkpr":"32",
               "kftc_deal_bas_r":"32.9","cur_nm":"태국 바트"},
              {"result":1,"cur_unit":"USD","ttb":"1,056.23","tts":"1,077.56","deal_bas_r":"1,066.9",
               "bkpr":"1,066","yy_efee_r":"2.69465","ten_dd_efee_r":"0.07485","kftc_bkpr":"1,071",
               "kftc_deal_bas_r":"1,071.4","cur_nm":"미국 달러"}
            ]
            """;

    private static final String CROSS_BASE_URL = "https://open.er-api.com";

    /** USD 기준 환율 응답(실측값을 줄인 것). {@code rates.CLP}는 "1 USD = 933.41 CLP"를 뜻한다. */
    private static final String CROSS_RESPONSE = """
            {"result":"success","base_code":"USD",
             "time_last_update_utc":"Fri, 31 Jul 2026 00:02:31 +0000",
             "rates":{"USD":1,"KRW":1429.51,"CLP":933.411875,"CDF":2288.625923,"ZAR":16.515495}}
            """;

    private ExchangeRateRepository rateRepository;
    private MockRestServiceServer server;
    private MockRestServiceServer crossServer;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder crossBuilder = RestClient.builder().baseUrl(CROSS_BASE_URL);
        crossServer = MockRestServiceServer.bindTo(crossBuilder).build();
        rateRepository = mock(ExchangeRateRepository.class);
        service = new ExchangeRateService(builder.build(), crossBuilder.build(), rateRepository);
        setConfig(AUTH_KEY, "USD,EUR,CNH,JPY,AUD,IDR,CAD,MYR");
        // 재정환율은 전용 테스트에서만 켠다 — 켜두면 모든 테스트가 두 번째 서버를 물어야 한다.
        ReflectionTestUtils.setField(service, "crossRateEnabled", false);
        ReflectionTestUtils.setField(service, "crossRateCurrencies", "CLP,ARS,CDF,ZAR,BRL,PHP");
    }

    @Test
    void parsesCommaSeparatedAmountsAndHundredUnitCurrencies() {
        LocalDate today = LocalDate.now(KST);
        expectFetch(today, EXIM_RESPONSE);

        ExchangeRateDto.RefreshResult result = service.refresh();

        server.verify();
        assertThat(result.status()).isEqualTo("SUCCESS");

        List<ExchangeRatePoint> saved = captureSaved();
        // THB는 노출 목록 밖인데도 저장된다 — 수집은 전량, 노출만 선택이기 때문이다.
        // 빠지는 것은 AUD(매매기준율 결손)와 DEM·KRW(ttb·tts가 둘 다 0 = 거래되지 않음)뿐이다.
        assertThat(saved).extracting(ExchangeRatePoint::getCurrencyCode)
                .containsExactlyInAnyOrder("CNH", "JPY", "THB", "USD");

        ExchangeRatePoint usd = find(saved, "USD");
        // "1,066.9" — 콤마를 떼지 않으면 여기서 깨진다.
        assertThat(usd.getDealBaseRate()).isEqualTo(1066.9);
        assertThat(usd.getTtb()).isEqualTo(1056.23);
        assertThat(usd.getTts()).isEqualTo(1077.56);
        assertThat(usd.getUnitMultiplier()).isEqualTo(1);
        assertThat(usd.getCurrencyName()).isEqualTo("미국 달러");
        assertThat(usd.getRateDate()).isEqualTo(today);

        ExchangeRatePoint jpy = find(saved, "JPY");
        // "JPY(100)" → 코드는 JPY, 단위는 100. 값은 원문(100엔당)을 그대로 보존한다.
        assertThat(jpy.getCurrencyCode()).isEqualTo("JPY");
        assertThat(jpy.getUnitMultiplier()).isEqualTo(100);
        assertThat(jpy.getDealBaseRate()).isEqualTo(951.05);
    }

    /**
     * 비영업일·11시 이전에는 빈 배열이 온다. 이때 하루씩 거슬러 올라가 직전 고시일을 찾아야
     * 주말에도 밴드가 채워진다 — 이 동작이 없으면 토·일 이틀 내내 화면이 빈다.
     */
    @Test
    void walksBackToPreviousBusinessDayWhenTodayHasNoQuote() {
        LocalDate today = LocalDate.now(KST);
        expectFetch(today, "[]");
        expectFetch(today.minusDays(1), EXIM_RESPONSE);

        ExchangeRateDto.RefreshResult result = service.refresh();

        server.verify();
        assertThat(result.status()).isEqualTo("SUCCESS");
        // 요청일이 아니라 "데이터가 실제로 있던 날짜"가 고시일로 저장돼야 한다.
        assertThat(result.savedDates()).containsExactly(today.minusDays(1).toString());
        assertThat(captureSaved()).allSatisfy(point ->
                assertThat(point.getRateDate()).isEqualTo(today.minusDays(1)));
    }

    /**
     * 인증 오류(result=3)·한도 초과(result=4)는 날짜를 바꿔도 결과가 같다. 거슬러 올라가면
     * 남은 호출 한도만 태우므로 즉시 중단해야 한다 — 호출이 정확히 1회인지가 이 테스트의 핵심이다.
     */
    @Test
    void stopsImmediatelyOnAuthErrorInsteadOfWalkingBack() {
        expectFetch(LocalDate.now(KST), "[{\"result\":3}]");

        ExchangeRateDto.RefreshResult result = service.refresh();

        server.verify();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("인증코드 오류");
        verify(rateRepository, never()).saveAll(any());
    }

    /** 키가 없으면 result=3만 돌아온다. 호출 자체를 하지 않아야 한다(부팅은 정상이어야 하고). */
    @Test
    void skipsCollectionEntirelyWhenAuthKeyIsMissing() {
        setConfig("", "USD");

        ExchangeRateDto.RefreshResult result = service.refresh();

        // MockRestServiceServer는 기대하지 않은 요청이 오면 실패한다 — 호출 0회가 검증된다.
        server.verify();
        assertThat(result.status()).isEqualTo("SKIPPED");
        verify(rateRepository, never()).saveAll(any());
    }

    /**
     * 등락은 "어제"가 아니라 <b>직전 고시일</b>과 비교한다. 월요일 화면이 (데이터가 없는) 일요일이
     * 아니라 금요일과 비교되게 하려면 이 기준이어야 한다.
     */
    @Test
    void computesChangeAgainstPreviousQuoteDateAndKeepsConfiguredOrder() {
        LocalDate friday = LocalDate.parse("2026-07-24");
        LocalDate monday = LocalDate.parse("2026-07-27");
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of(
                        // 리포지토리 계약대로 통화·날짜 오름차순
                        point("JPY", friday, "일본 옌", 100, 950.0),
                        point("JPY", monday, "일본 옌", 100, 940.0),
                        point("USD", friday, "미국 달러", 1, 1000.0),
                        point("USD", monday, "미국 달러", 1, 1010.0)));

        ExchangeRateDto.ExchangeRateBoard board = service.exchangeRates();

        assertThat(board.baseCurrency()).isEqualTo("KRW");
        // 요청일이 아니라 실제 고시일. 프론트가 "언제 기준"인지 표시하는 근거다.
        assertThat(board.rateDate()).isEqualTo("2026-07-27");
        // 설정 순서(USD,CNH,JPY,...)를 따른다 — 데이터가 없는 통화는 건너뛰되 순서는 유지된다.
        assertThat(board.rates()).extracting(ExchangeRateDto.ExchangeRateItem::currencyCode)
                .containsExactly("USD", "JPY");

        ExchangeRateDto.ExchangeRateItem usd = board.rates().get(0);
        assertThat(usd.rate()).isEqualTo(1010.0);
        assertThat(usd.changeAmount()).isEqualTo(10.0);
        assertThat(usd.changeRate()).isEqualTo(1.0);
        assertThat(usd.changeLabel()).isEqualTo("▲ 1.00%");
        assertThat(usd.label()).isEqualTo("USD/KRW");

        ExchangeRateDto.ExchangeRateItem jpy = board.rates().get(1);
        assertThat(jpy.changeAmount()).isEqualTo(-10.0);
        assertThat(jpy.changeLabel()).isEqualTo("▼ 1.05%");
        // 100단위 고시임이 표기에 드러나야 한다. "JPY/KRW 940"은 100배 틀린 값으로 읽힌다.
        assertThat(jpy.label()).isEqualTo("JPY(100)/KRW");
    }

    /**
     * 수집과 노출이 분리돼 있음을 반대편에서 확인한다 — DB에 있어도 설정에 없으면 응답에 안 나간다.
     *
     * <p>이 분리 덕분에 통화를 추가할 때 설정 한 줄만 바꾸면 되고, 과거 이력이 이미 쌓여 있어
     * 전일 대비가 첫날부터 나온다.
     */
    @Test
    void hidesStoredCurrenciesThatAreNotConfiguredForDisplay() {
        LocalDate date = LocalDate.parse("2026-07-27");
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of(
                        point("THB", date, "태국 바트", 1, 32.9),
                        point("USD", date, "미국 달러", 1, 1010.0)));

        ExchangeRateDto.ExchangeRateBoard board = service.exchangeRates();

        assertThat(board.rates()).extracting(ExchangeRateDto.ExchangeRateItem::currencyCode)
                .containsExactly("USD");
        // 저장은 돼 있으므로, 설정에 THB를 넣는 순간 이력까지 그대로 따라온다.
        assertThat(board.rateDate()).isEqualTo("2026-07-27");
    }

    /** 비교할 직전 고시일이 없으면 등락을 0%로 지어내지 말고 "없음"으로 내려야 한다. */
    @Test
    void reportsNoChangeWhenThereIsOnlyOneQuoteDate() {
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of(point("USD", LocalDate.parse("2026-07-27"), "미국 달러", 1, 1010.0)));

        ExchangeRateDto.ExchangeRateItem usd = service.exchangeRates().rates().get(0);

        assertThat(usd.changeAmount()).isNull();
        assertThat(usd.changeRate()).isNull();
        assertThat(usd.changeLabel()).isEqualTo("—");
    }

    // ── 재정환율(USD 경유) ────────────────────────────────────────────

    /**
     * 재정환율의 KRW 다리는 <b>수출입은행 고시</b>여야 한다.
     *
     * <p>외부 소스도 KRW를 주지만(응답의 1429.51), 그걸 쓰면 같은 밴드 안에서 직접 고시
     * USD/KRW(1066.9)와 기준이 갈라져 사용자 눈에 불일치로 잡힌다. 그래서 기대값을 1066.9로
     * 계산해 두고, 만약 구현이 외부 소스의 KRW를 쓰면 값이 크게 어긋나 이 테스트가 깨진다.
     */
    @Test
    void computesCrossRatesAnchoredToKoreaEximUsdLeg() {
        LocalDate today = LocalDate.now(KST);
        enableCrossRates();
        expectFetch(today, EXIM_RESPONSE);
        stubUsdLeg(today, 1066.9);
        crossServer.expect(once(), requestTo(CROSS_BASE_URL + "/v6/latest/USD"))
                .andRespond(withSuccess(CROSS_RESPONSE, MediaType.APPLICATION_JSON));

        service.refresh();

        server.verify();
        crossServer.verify();

        List<ExchangeRatePoint> cross = captureAllSaved().stream()
                .filter(p -> ExchangeRatePoint.SOURCE_CROSS_USD.equals(p.getRateSource()))
                .toList();
        // 응답에 없는 ARS·BRL·PHP는 건너뛰고, 있는 셋만 만들어진다(없다고 실패하지 않는다).
        assertThat(cross).extracting(ExchangeRatePoint::getCurrencyCode)
                .containsExactlyInAnyOrder("CLP", "CDF", "ZAR");

        // CLP/KRW = (수출입은행 USD/KRW) / (외부소스 USD/CLP) x 단위배수
        assertThat(find(cross, "CLP").getDealBaseRate())
                .isCloseTo(1066.9 / 933.411875 * 100, within(0.0001));
        assertThat(find(cross, "ZAR").getDealBaseRate())
                .isCloseTo(1066.9 / 16.515495, within(0.0001));

        // 1원 미만이 되는 저가 통화는 100단위로 표기해야 읽힌다(콩고 프랑은 1단위면 0.47원).
        assertThat(find(cross, "CDF").getUnitMultiplier()).isEqualTo(100);
        assertThat(find(cross, "CLP").getUnitMultiplier()).isEqualTo(100);
        assertThat(find(cross, "ZAR").getUnitMultiplier()).isEqualTo(1);

        // 전신환 송금값은 원천에 없다. 0으로 채우면 "수수료 0원"으로 오독된다.
        assertThat(find(cross, "CLP").getTtb()).isNull();
        assertThat(find(cross, "CLP").getTts()).isNull();
    }

    /**
     * 재정환율은 부가 정보다. 외부 소스가 죽어도 이미 받은 직접 고시까지 실패로 만들면 안 된다 —
     * 밴드 전체가 비는 것보다 6종이 비는 편이 훨씬 낫다.
     */
    @Test
    void crossRateFailureDoesNotBreakDirectQuotes() {
        LocalDate today = LocalDate.now(KST);
        enableCrossRates();
        expectFetch(today, EXIM_RESPONSE);
        stubUsdLeg(today, 1066.9);
        crossServer.expect(once(), requestTo(CROSS_BASE_URL + "/v6/latest/USD"))
                .andRespond(withServerError());

        ExchangeRateDto.RefreshResult result = service.refresh();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(captureAllSaved()).extracting(ExchangeRatePoint::getCurrencyCode)
                .contains("USD", "CNH", "JPY");
    }

    /** 프론트가 근사값 표시를 달 수 있도록 재정환율임이 응답에 드러나야 한다. */
    @Test
    void marksCrossRatesInThePublicResponse() {
        LocalDate date = LocalDate.parse("2026-07-27");
        ReflectionTestUtils.setField(service, "currencies", "USD,CLP");
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of(
                        ExchangeRatePoint.cross("CLP", date, "칠레 페소", 100, 114.3),
                        point("USD", date, "미국 달러", 1, 1066.9)));

        List<ExchangeRateDto.ExchangeRateItem> rates = service.exchangeRates().rates();

        ExchangeRateDto.ExchangeRateItem usd = rates.get(0);
        assertThat(usd.crossRate()).isFalse();
        assertThat(usd.rateSource()).isEqualTo("KOREAEXIM");

        ExchangeRateDto.ExchangeRateItem clp = rates.get(1);
        assertThat(clp.crossRate()).isTrue();
        assertThat(clp.rateSource()).isEqualTo("CROSS_USD");
        assertThat(clp.label()).isEqualTo("CLP(100)/KRW");
    }

    /**
     * 단위 배수가 다른 두 행은 비교하면 안 된다. 100단위 값에서 1단위 값을 빼면 등락이 수천 %로
     * 튀는데 예외는 안 나므로, 조용히 틀리는 것을 막는 안전장치다.
     */
    @Test
    void skipsChangeCalculationWhenUnitMultiplierDiffers() {
        ReflectionTestUtils.setField(service, "currencies", "CLP");
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of(
                        ExchangeRatePoint.cross("CLP", LocalDate.parse("2026-07-24"), "칠레 페소", 1, 1.143),
                        ExchangeRatePoint.cross("CLP", LocalDate.parse("2026-07-27"), "칠레 페소", 100, 114.3)));

        ExchangeRateDto.ExchangeRateItem clp = service.exchangeRates().rates().get(0);

        assertThat(clp.changeAmount()).isNull();
        assertThat(clp.changeLabel()).isEqualTo("—");
    }

    /** 데이터가 하나도 없어도 200으로 빈 밴드를 준다 — 프론트가 404를 따로 다루지 않아도 되게. */
    @Test
    void returnsEmptyBoardWhenNothingCollectedYet() {
        when(rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(any()))
                .thenReturn(List.of());

        ExchangeRateDto.ExchangeRateBoard board = service.exchangeRates();

        assertThat(board.rateDate()).isNull();
        assertThat(board.rates()).isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────────────

    private void setConfig(String authKey, String currencies) {
        ReflectionTestUtils.setField(service, "authKey", authKey);
        ReflectionTestUtils.setField(service, "currencies", currencies);
        ReflectionTestUtils.setField(service, "schedulerEnabled", true);
    }

    private void expectFetch(LocalDate date, String responseBody) {
        server.expect(once(), requestTo(BASE_URL + "/site/program/financial/exchangeJSON"
                        + "?authkey=" + AUTH_KEY
                        + "&searchdate=" + date.format(SEARCH_DATE)
                        + "&data=AP01"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    @SuppressWarnings("unchecked")
    private List<ExchangeRatePoint> captureSaved() {
        ArgumentCaptor<List<ExchangeRatePoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(rateRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static ExchangeRatePoint find(List<ExchangeRatePoint> points, String code) {
        return points.stream()
                .filter(point -> point.getCurrencyCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError(code + " 가 저장되지 않았습니다."));
    }

    private static ExchangeRatePoint point(
            String code, LocalDate date, String name, int multiplier, double rate) {
        return ExchangeRatePoint.direct(code, date, name, multiplier, rate, null, null);
    }

    /** saveAll이 직접 고시분·재정환율분으로 두 번 불리므로 호출 전체를 모아 본다. */
    @SuppressWarnings("unchecked")
    private List<ExchangeRatePoint> captureAllSaved() {
        ArgumentCaptor<List<ExchangeRatePoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(rateRepository, atLeastOnce()).saveAll(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private void enableCrossRates() {
        ReflectionTestUtils.setField(service, "crossRateEnabled", true);
    }

    /** 재정환율 계산이 KRW 다리로 쓸 수출입은행 USD 행. */
    private void stubUsdLeg(LocalDate date, double usdKrw) {
        when(rateRepository.findById(new ExchangeRatePoint.Key("USD", date)))
                .thenReturn(Optional.of(point("USD", date, "미국 달러", 1, usdKrw)));
    }
}

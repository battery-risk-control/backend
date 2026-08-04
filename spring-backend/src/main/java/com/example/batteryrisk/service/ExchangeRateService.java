package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.ExchangeRatePoint;
import com.example.batteryrisk.dto.ExchangeRateDto;
import com.example.batteryrisk.repository.ExchangeRateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 환율(공개 대시보드 ② 밴드)의 수집·저장·조회를 담당한다.
 *
 * <p><b>수집 경로:</b> Spring 스케줄러 → 한국수출입은행 현재환율 API → {@code exchange_rate_points}
 * upsert. FastAPI를 거치지 않는다 — AI/ML이 아니라 단순 REST 조회라 Spring이 직접 부르는 편이
 * 홉이 하나 줄고 장애 지점도 적다(가격 추이가 FastAPI를 거치는 건 yfinance가 파이썬 라이브러리라서다).
 *
 * <p><b>원천의 제약이 로직을 결정한다.</b> 이 API는 "일환율"이라 영업일 11시 전후에 하루 한 번만
 * 갱신되고, 비영업일이나 영업당일 11시 이전 요청에는 빈 배열이 돌아온다. 그래서
 * <ul>
 *   <li>요청일에 데이터가 없으면 <b>날짜를 하루씩 거슬러 올라가며</b> 직전 고시일을 찾는다.
 *       주말·연휴에도 화면이 비지 않게 하려면 이 방법뿐이다.</li>
 *   <li>갱신 시각(11:00) 직후인 11:20과, 갱신이 늦어졌을 때를 위한 16:00에 각각 돈다.
 *       upsert라 같은 날짜를 두 번 받아도 중복이 생기지 않는다.</li>
 *   <li>조회는 원천을 부르지 않고 DB의 <b>가장 최근 고시일</b>을 본다. 공개 화면이 외부 API
 *       응답 시간과 일일 호출 한도(1000회)에 묶이면 안 되기 때문이다.</li>
 * </ul>
 *
 * <p><b>인증키:</b> {@code KOREAEXIM_API_KEY} 미설정이면 수집 전체를 건너뛴다(부팅은 정상).
 * 키 없이 호출하면 result=3만 돌아오므로 시도할 이유가 없다.
 */
@Service
public class ExchangeRateService {
    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 직전 고시일을 찾을 때 거슬러 올라가는 최대 일수. 설·추석 연휴가 주말과 붙으면 휴장이
     * 5~6일까지 이어지므로 10일이면 충분하다. 한 번 거슬러 올라갈 때마다 1회 호출이라
     * 최악의 경우에도 하루 20회(2회 스케줄 × 10일)로 일일 한도 1000회에 한참 못 미친다.
     */
    private static final int MAX_LOOKBACK_DAYS = 10;

    /**
     * 공개 조회가 훑는 구간. 전일 대비를 계산하려면 "직전 고시일" 행이 필요한데, 연휴가 끼면
     * 직전 고시일이 열흘 전일 수도 있어 lookback보다 넉넉히 잡는다.
     */
    private static final int LOOKUP_DAYS = 20;

    /** 원문 {@code cur_unit} 파싱. "USD" -> (USD, 1), "JPY(100)" -> (JPY, 100). */
    private static final Pattern CUR_UNIT = Pattern.compile("^([A-Z]{3})(?:\\((\\d+)\\))?$");

    /** 조회 결과 코드(원문 result) -> 사람이 읽을 수 있는 사유. */
    private static final Map<Integer, String> RESULT_MESSAGE = Map.of(
            2, "DATA 코드 오류(data=AP01 확인 필요)",
            3, "인증코드 오류 — 인증키가 틀렸거나, 개인정보 보유기간(2년) 만료로 파기된 키입니다. 재발급이 필요합니다.",
            4, "일일 제한 횟수(1000회) 마감");

    /**
     * 재정환율로 만들 통화의 고시 단위. 원천이 100단위로 고시하는 JPY·IDR과 같은 이유로,
     * 1원 미만이 되는 저가 통화는 100단위로 표기해야 화면에서 읽힌다
     * (콩고 프랑은 1단위면 0.62원이다).
     *
     * <p><b>값이 아니라 통화로 고정한다.</b> "1원 미만이면 100단위"처럼 값으로 판정하면 환율이
     * 경계를 넘나들 때 배수가 날마다 바뀌고, 그러면 전일 대비가 1단위와 100단위를 비교하게 된다.
     */
    private static final Map<String, Integer> CROSS_UNIT_MULTIPLIER =
            Map.of("CLP", 100, "ARS", 100, "CDF", 100);

    /** 재정환율 통화의 한글 표기. 원천이 통화명을 주지 않아 여기서 붙인다. */
    private static final Map<String, String> CROSS_CURRENCY_NAME = Map.of(
            "CLP", "칠레 페소",
            "ARS", "아르헨티나 페소",
            "CDF", "콩고 프랑",
            "ZAR", "남아프리카 랜드",
            "BRL", "브라질 헤알",
            "PHP", "필리핀 페소");

    private final RestClient koreaEximRestClient;
    private final RestClient crossRateRestClient;
    private final ExchangeRateRepository rateRepository;

    /**
     * 원문을 JSON 그대로 받아 직접 파싱하기 위한 매퍼. 메시지 컨버터에 맡기지 않는 이유는
     * 이 API가 Content-Type을 항상 application/json으로 주지 않고, 데이터가 없을 때 빈 본문을
     * 내려보내는 경우도 있어서다 — 문자열로 받아야 그 두 경우를 예외 없이 구분할 수 있다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OpenAPI 신청 시 발급받은 인증키. 비어 있으면 수집을 통째로 건너뛴다. */
    @Value("${app.exchange-rate.auth-key:}")
    private String authKey;

    /** 자동 갱신 on/off. 무료 공개 API라 기본 true지만, 오프라인 시연 등에서 끌 수 있게 열어둔다. */
    @Value("${app.exchange-rate.scheduler-enabled:true}")
    private boolean schedulerEnabled;

    /**
     * 화면에 <b>노출할</b> 통화. 수집 범위가 아니라 표시 대상이며, <b>설정 순서가 곧 노출 순서</b>다
     * (마퀴 옆 교대 표기). 여기에 없는 통화도 DB에는 쌓이므로, 추가할 때 설정만 바꾸면
     * 과거 이력까지 바로 따라온다.
     *
     * <p>기본값은 ERP 실데이터에서 뽑았다.
     * <ul>
     *   <li><b>USD·EUR</b> — 실제 결제통화. purchase_orders 92건이 USD 49 / EUR 23 / KRW 20으로,
     *       예외 없이 이 셋뿐이다. KRW은 기준통화라 환율이 항상 1이므로 뺀다.</li>
     *   <li><b>CNH·JPY·AUD·IDR·CAD·MYR</b> — 결제는 USD·EUR로 하지만 조달 자체가 걸린 나라들이다
     *       (중국 2곳·인니 2곳·호주·일본·캐나다·말레이시아). 현지 통화가 흔들리면 재계약 단가로
     *       옮겨붙으므로 공급망 화면에 띄울 값어치가 있다.</li>
     * </ul>
     *
     * <p><b>칠레·아르헨티나(리튬), 콩고민주공화국(코발트), 남아공(망간), 브라질, 필리핀(니켈)은
     * 넣을 수 없다.</b> 핵심 조달국인데도 CLP·ARS·CDF·ZAR·BRL·PHP를 이 API가 고시하지 않는다
     * (국내 은행권이 매매하는 통화만 준다). 이 나라들의 환율이 필요해지면 별도 소스를 붙여야 한다.
     */
    @Value("${app.exchange-rate.currencies:"
            + "USD,EUR,CNH,CLP,AUD,IDR,CDF,ARS,ZAR,BRL,PHP,JPY,CAD,MYR}")
    private String currencies;

    /** 재정환율 수집 on/off. 외부 소스가 하나 더 붙는 경로라 통째로 끌 수 있게 둔다. */
    @Value("${app.exchange-rate.cross-rate.enabled:true}")
    private boolean crossRateEnabled;

    /**
     * USD를 경유해 만들 통화. 수출입은행이 고시하지 않는 조달국들이다 —
     * 칠레·아르헨티나(리튬), 콩고민주공화국(코발트), 남아공(망간), 브라질, 필리핀(니켈).
     */
    @Value("${app.exchange-rate.cross-rate.currencies:CLP,ARS,CDF,ZAR,BRL,PHP}")
    private String crossRateCurrencies;

    // RestClient 빈이 셋(fastApi·koreaExim·crossRate)이라 이름만 믿지 않고 명시적으로 지정한다 —
    // 바뀌어 섞이면 엉뚱한 호스트로 요청이 나가고, 그건 런타임에야 드러난다.
    public ExchangeRateService(
            @Qualifier("koreaEximRestClient") RestClient koreaEximRestClient,
            @Qualifier("crossRateRestClient") RestClient crossRateRestClient,
            ExchangeRateRepository rateRepository) {
        this.koreaEximRestClient = koreaEximRestClient;
        this.crossRateRestClient = crossRateRestClient;
        this.rateRepository = rateRepository;
    }

    /**
     * 평일 11:20과 16:00(KST). 11:20은 원천 갱신(11시 전후) 직후이고, 16:00은 갱신이 늦어졌을 때의
     * 재시도다. 주말에는 새 고시가 없으므로 아예 돌지 않는다 — 돌아도 빈 응답만 받는다.
     */
    @Scheduled(cron = "0 20 11,16 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshDaily() {
        if (!schedulerEnabled) {
            log.debug("환율 자동 갱신 비활성(app.exchange-rate.scheduler-enabled=false) — 건너뜀");
            return;
        }
        refresh();
    }

    /**
     * 최초 기동 시 데이터가 하나도 없으면 즉시 채운다 — 그러지 않으면 다음 평일 11:20까지 밴드가 빈다.
     *
     * <p>이때는 첫 고시일에서 멈추지 않고 {@link #MAX_LOOKBACK_DAYS}일치를 모두 받는다.
     * 전일 대비 등락은 <b>직전 고시일 행이 있어야</b> 계산되는데, 하루치만 받으면 첫날 화면의
     * 등락이 전부 "—"로 나오기 때문이다. 이미 데이터가 있으면 아무 것도 하지 않으므로
     * 재기동이 잦아도 부담이 없다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnFirstBoot() {
        if (!schedulerEnabled || rateRepository.count() > 0) {
            return;
        }
        log.info("환율 데이터가 비어 있어 최근 {}일 최초 적재를 시작합니다.", MAX_LOOKBACK_DAYS);
        collect(false);
    }

    /** 최신 고시일 한 건만 채운다(스케줄러 경로). */
    public ExchangeRateDto.RefreshResult refresh() {
        return collect(true);
    }

    /**
     * 원천에서 환율을 받아 저장한다. 실패해도 예외를 밖으로 던지지 않는다 —
     * 스케줄러/기동 훅에서 호출하므로 여기서 터지면 애플리케이션 상태에 영향을 준다
     * (특히 기동 훅에서 예외가 나가면 애플리케이션 기동 자체가 실패한다).
     *
     * @param stopAtFirstHit true면 데이터가 있는 첫 날짜에서 멈추고, false면 구간 전체를 훑는다.
     */
    private ExchangeRateDto.RefreshResult collect(boolean stopAtFirstHit) {
        if (authKey.isBlank()) {
            log.info("환율 인증키 미설정(app.exchange-rate.auth-key / KOREAEXIM_API_KEY) — 수집을 건너뜁니다.");
            return new ExchangeRateDto.RefreshResult(0, List.of(), "SKIPPED", "인증키 미설정");
        }

        List<String> savedDates = new ArrayList<>();
        int savedRates = 0;
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        // 재정환율은 가장 최근 고시일에만 붙인다(외부 소스가 과거 조회를 제공하지 않는다).
        LocalDate latestSaved = null;

        for (int back = 0; back < MAX_LOOKBACK_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            List<ExchangeRateDto.KoreaEximRate> raw;
            try {
                raw = fetch(date);
            } catch (RuntimeException exception) {
                // 인증 오류·한도 초과는 날짜를 바꿔도 결과가 같으므로 거슬러 올라가지 않고 즉시 중단한다.
                log.warn("환율 수집 중단({}): {}", date, exception.getMessage());
                return new ExchangeRateDto.RefreshResult(
                        savedRates, savedDates, "FAILED", exception.getMessage());
            }
            if (raw.isEmpty()) {
                // 비영업일이거나 영업당일 11시 이전. 하루 거슬러 올라가 직전 고시일을 찾는다.
                continue;
            }

            List<ExchangeRatePoint> points = toPoints(raw, date);
            if (points.isEmpty()) {
                // 응답은 왔는데 거래 가능한 통화가 한 건도 없다 — 원천 응답 형식이 바뀌었을
                // 가능성이 높다(예: cur_unit 표기 변경). 날짜를 바꿔도 같으므로 중단한다.
                log.warn("환율 응답({})에서 거래 가능한 통화를 찾지 못했습니다. 원천 응답 형식 변경 여부를 확인하세요.",
                        date);
                return new ExchangeRateDto.RefreshResult(
                        savedRates, savedDates, "FAILED", "거래 가능한 통화 없음");
            }

            // 복합키(통화, 고시일)라 이미 있는 날짜는 갱신, 없으면 삽입으로 수렴한다.
            // saveAll 자체가 트랜잭션이라 날짜별로 독립 커밋된다 — 중간 날짜가 실패해도
            // 앞서 받은 날짜는 남는다(backfill이 통째로 날아가지 않는다).
            rateRepository.saveAll(points);
            savedRates += points.size();
            savedDates.add(date.toString());
            if (latestSaved == null) {
                // 오늘부터 거슬러 올라가므로 처음 성공한 날짜가 곧 가장 최근 고시일이다.
                latestSaved = date;
            }

            if (stopAtFirstHit) {
                break;
            }
        }

        if (savedDates.isEmpty()) {
            log.warn("환율 수집 결과 없음 — 최근 {}일간 고시 데이터를 찾지 못했습니다.", MAX_LOOKBACK_DAYS);
            return new ExchangeRateDto.RefreshResult(0, List.of(), "EMPTY", "최근 고시 데이터 없음");
        }

        savedRates += collectCrossRates(latestSaved);
        log.info("환율 갱신 완료: {}건 저장, 고시일 {}", savedRates, savedDates);
        return new ExchangeRateDto.RefreshResult(savedRates, savedDates, "SUCCESS", null);
    }

    /**
     * 수출입은행이 고시하지 않는 조달국 통화를 USD를 경유해 만든다.
     *
     * <pre>
     *   CLP/KRW = (수출입은행 USD/KRW) / (외부소스 USD/CLP)
     * </pre>
     *
     * <p><b>KRW 다리를 수출입은행 고시로 고정하는 것이 핵심이다.</b> 외부 소스의 KRW를 쓰면 같은
     * 밴드 안에서 직접 고시 USD/KRW와 재정환율의 기준이 갈라져, 사용자가 눈으로 잡아낼 수 있는
     * 불일치가 된다. 서로 다른 환율 소스는 스냅샷 시각이 달라 항상 조금씩 어긋난다 — 무료 소스
     * 두 곳(open.er-api·ECB)을 비교했을 때 USD/KRW가 0.29%, 통화별 재정환율이 중앙값 0.14%
     * 어긋났다. <b>수출입은행 대비 실측은 아직 없다</b>(인증키가 있어야 하며, 발급 후 확인 필요).
     *
     * <p><b>한계:</b> 무료 등급은 "현재" 시세만 주고 과거 조회가 없다. 그래서 최초 적재 때 직접
     * 고시는 10일치가 쌓여도 재정환율은 하루치뿐이라, 첫날은 전일 대비가 "—"로 뜬다(다음날 해소).
     * 또 고시일이 오늘이 아닐 때(주말·연휴)는 오늘 시세를 그 고시일에 붙이게 되는데, 밴드 안의
     * 날짜를 하나로 맞추는 편이 낫다고 보고 그렇게 둔다.
     *
     * <p>실패해도 예외를 밖으로 내지 않는다 — 재정환율은 부가 정보이고, 여기서 터져서 이미 받은
     * 직접 고시까지 실패로 만들 이유가 없다.
     *
     * @return 저장한 재정환율 건수
     */
    private int collectCrossRates(LocalDate rateDate) {
        List<String> targets = splitCodes(crossRateCurrencies);
        if (!crossRateEnabled || targets.isEmpty() || rateDate == null) {
            return 0;
        }
        try {
            // KRW 다리. 방금 저장한 그 고시일의 수출입은행 USD를 그대로 쓴다.
            ExchangeRatePoint usdLeg = rateRepository
                    .findById(new ExchangeRatePoint.Key("USD", rateDate))
                    .orElse(null);
            if (usdLeg == null) {
                log.warn("재정환율 계산 건너뜀 — {} 고시일의 USD/KRW가 없습니다.", rateDate);
                return 0;
            }

            ExchangeRateDto.CrossRateResponse response = crossRateRestClient.get()
                    .uri("/v6/latest/USD")
                    .retrieve()
                    .body(ExchangeRateDto.CrossRateResponse.class);
            if (response == null || !"success".equals(response.result()) || response.rates() == null) {
                throw new IllegalStateException(
                        "USD 기준 환율 응답이 올바르지 않습니다: "
                                + (response == null ? "본문 없음" : response.result()));
            }

            List<ExchangeRatePoint> points = new ArrayList<>();
            for (String code : targets) {
                Double usdToForeign = response.rates().get(code);
                // 1 USD = N 통화 형태다. 0이면 나눌 수 없고, 없으면 원천이 그 통화를 뺀 것이다.
                if (usdToForeign == null || usdToForeign == 0) {
                    log.warn("재정환율 소스에 {}가 없습니다 — 건너뜁니다.", code);
                    continue;
                }
                int multiplier = CROSS_UNIT_MULTIPLIER.getOrDefault(code, 1);
                double rate = usdLeg.getDealBaseRate() / usdToForeign * multiplier;
                points.add(ExchangeRatePoint.cross(
                        code, rateDate, CROSS_CURRENCY_NAME.getOrDefault(code, code),
                        multiplier, round(rate, 4)));
            }
            if (points.isEmpty()) {
                return 0;
            }
            rateRepository.saveAll(points);
            log.info("재정환율 {}건 계산 완료(고시일 {}, USD/KRW {} 기준)",
                    points.size(), rateDate, usdLeg.getDealBaseRate());
            return points.size();
        } catch (RuntimeException exception) {
            log.warn("재정환율 계산 실패(직접 고시는 정상 저장됨): {}", exception.toString());
            return 0;
        }
    }

    /**
     * 특정 날짜의 고시환율을 조회한다. 데이터가 없는 날(비영업일·11시 이전)은 빈 리스트를 돌려주고,
     * 인증 오류·한도 초과 등 재시도해도 소용없는 상황에서만 예외를 던진다.
     */
    private List<ExchangeRateDto.KoreaEximRate> fetch(LocalDate date) {
        // 인증키가 쿼리 파라미터로 들어가므로 URI를 로그에 남기지 않는다.
        String body = koreaEximRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/site/program/financial/exchangeJSON")
                        .queryParam("authkey", authKey)
                        .queryParam("searchdate", date.format(SEARCH_DATE))
                        .queryParam("data", "AP01")
                        .build())
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) {
            return List.of();
        }

        List<ExchangeRateDto.KoreaEximRate> rates;
        try {
            rates = objectMapper.readValue(
                    body, new TypeReference<List<ExchangeRateDto.KoreaEximRate>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("환율 응답을 해석하지 못했습니다: " + exception.getMessage());
        }
        if (rates.isEmpty()) {
            return List.of();
        }

        // 오류일 때는 result만 담긴 행 하나가 돌아온다. 성공(1)이 아니면 사유를 붙여 중단시킨다.
        Integer result = rates.get(0).result();
        if (result != null && result != 1) {
            throw new IllegalStateException(
                    RESULT_MESSAGE.getOrDefault(result, "알 수 없는 결과 코드 " + result));
        }
        return rates;
    }

    /**
     * 원문 행을 엔티티로 옮긴다.
     *
     * <p><b>노출 통화로 걸러내지 않고 거래 가능한 통화를 전부 저장한다.</b> 원천은 한 번의 호출로
     * 전 통화를 주므로 골라 담아도 호출 비용이 같은데, 버리면 나중에 통화를 추가할 때 과거 이력이
     * 없어 전일 대비가 한동안 "—"로 뜬다. 노출 대상은 조회 시점에 고르면 된다
     * ({@link #exchangeRates()}) — 그때는 설정만 바꿔도 이력이 이미 쌓여 있다.
     *
     * <p>버리는 것은 <b>실제로 거래되지 않는 통화</b>뿐이다. 원천에는 유로 도입 전 폐지 통화
     * (마르크·프랑·리라 등 8종)와 국내 은행이 매매하지 않는 통화(XOF)가 섞여 있는데, 이들은
     * 매매기준율만 남아 있고 전신환 송금값(ttb·tts)이 둘 다 0이다. 기준통화인 KRW도 같은 모양
     * (기준율 1, ttb·tts 0)이라 함께 걸러진다 — 원화를 원화로 환산해 보여줄 이유는 없다.
     */
    private static List<ExchangeRatePoint> toPoints(
            List<ExchangeRateDto.KoreaEximRate> raw, LocalDate date) {
        List<ExchangeRatePoint> points = new ArrayList<>();
        for (ExchangeRateDto.KoreaEximRate rate : raw) {
            if (rate.curUnit() == null) {
                continue;
            }
            Matcher matcher = CUR_UNIT.matcher(rate.curUnit().trim());
            if (!matcher.matches()) {
                continue;
            }
            Double dealBase = parseAmount(rate.dealBasR());
            // 매매기준율이 없거나 0인 행은 데이터 결손이다. 화면에 "0원"으로 띄우면 값이 없는
            // 것보다 나쁘게 오해를 부른다.
            if (dealBase == null || dealBase == 0) {
                continue;
            }
            Double ttb = parseAmount(rate.ttb());
            Double tts = parseAmount(rate.tts());
            if (isZeroOrNull(ttb) && isZeroOrNull(tts)) {
                continue;
            }
            int multiplier = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            points.add(ExchangeRatePoint.direct(
                    matcher.group(1), date,
                    rate.curNm() == null ? matcher.group(1) : rate.curNm().trim(),
                    multiplier, dealBase, ttb, tts));
        }
        return points;
    }

    private static boolean isZeroOrNull(Double value) {
        return value == null || value == 0;
    }

    /** 원문 금액은 {@code "1,056.23"}처럼 천 단위 콤마가 들어 있다. */
    private static Double parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // ── 공개 조회 ──────────────────────────────────────────────────────

    /**
     * 공개 환율 밴드. 원천을 부르지 않고 DB에 쌓인 <b>가장 최근 고시일</b>을 반환한다 —
     * 주말·공휴일에도 직전 영업일 환율이 그대로 보이고, 응답이 외부 API 지연에 묶이지 않는다.
     *
     * <p>등락은 "어제"가 아니라 <b>그 통화의 직전 고시일</b>과 비교한다. 월요일 화면이 일요일
     * (데이터 없음) 대신 금요일과 비교되게 하려면 이 기준이어야 한다.
     */
    public ExchangeRateDto.ExchangeRateBoard exchangeRates() {
        Map<String, List<ExchangeRatePoint>> grouped = groupByCurrency();

        // 설정에 적힌 순서를 그대로 노출 순서로 쓴다(마퀴 옆 교대 표기의 순서가 매번 바뀌면 안 된다).
        // DB에는 노출 대상이 아닌 통화도 쌓여 있으므로 여기서 골라낸다.
        List<List<ExchangeRatePoint>> selected = targetCurrencies().stream()
                .map(grouped::get)
                .filter(points -> points != null && !points.isEmpty())
                .toList();

        // 밴드 상단에 표기할 고시일. 노출하지 않는 통화는 계산에서 뺀다 — 화면에 없는 통화 때문에
        // "○월 ○일 기준"만 최신으로 앞서 나가면 표기가 실제 값과 어긋난다.
        LocalDate latest = selected.stream()
                .map(points -> points.get(points.size() - 1).getRateDate())
                .max(LocalDate::compareTo)
                .orElse(null);

        List<ExchangeRateDto.ExchangeRateItem> items =
                selected.stream().map(ExchangeRateService::toItem).toList();

        return new ExchangeRateDto.ExchangeRateBoard(
                latest == null ? null : latest.toString(), "KRW", items);
    }

    /**
     * 통화 1단위당 원화 환산율. 금액 환산이 필요한 다른 기능(수입 의존도 등)이 쓴다.
     *
     * <p><b>고시 단위를 1단위로 정규화해 돌려준다</b> — 저장값은 표기 관례대로 100엔당 951.05원
     * 같은 원문이라, 그대로 곱하면 100배 틀린 금액이 나온다. 여기서 배수를 나눠 없앤다.
     *
     * <p>KRW는 원천이 고시하지 않아(기준통화) 1.0을 직접 넣는다. 이게 없으면 원화 발주가 통째로
     * 환산 실패로 빠져 국내 조달 비중이 0이 된다.
     *
     * @return 통화코드 → 1단위당 원화. 수집 전이면 KRW 하나만 들어 있다.
     */
    public Map<String, Double> latestRatesToKrw() {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("KRW", 1.0);
        for (Map.Entry<String, List<ExchangeRatePoint>> entry : groupByCurrency().entrySet()) {
            List<ExchangeRatePoint> points = entry.getValue();
            ExchangeRatePoint latest = points.get(points.size() - 1);
            rates.put(entry.getKey(), latest.getDealBaseRate() / latest.getUnitMultiplier());
        }
        return rates;
    }

    /** 환산에 쓴 환율의 고시일(가장 최근). 화면에 "언제 기준 환산인지" 밝히는 데 쓴다. */
    public LocalDate latestRateDate() {
        return groupByCurrency().values().stream()
                .map(points -> points.get(points.size() - 1).getRateDate())
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    /** 조회 구간의 환율을 통화별로 묶는다(리포지토리가 통화·날짜 오름차순으로 반환). */
    private Map<String, List<ExchangeRatePoint>> groupByCurrency() {
        LocalDate from = LocalDate.now(SERVICE_ZONE).minusDays(LOOKUP_DAYS);
        Map<String, List<ExchangeRatePoint>> grouped = new LinkedHashMap<>();
        for (ExchangeRatePoint point :
                rateRepository.findByRateDateGreaterThanEqualOrderByCurrencyCodeAscRateDateAsc(from)) {
            grouped.computeIfAbsent(point.getCurrencyCode(), key -> new ArrayList<>()).add(point);
        }
        return grouped;
    }

    /** 통화별 마지막 점을 현재 환율로, 그 직전 점을 비교 대상으로 삼는다. */
    private static ExchangeRateDto.ExchangeRateItem toItem(List<ExchangeRatePoint> points) {
        ExchangeRatePoint current = points.get(points.size() - 1);
        ExchangeRatePoint previous = points.size() >= 2 ? points.get(points.size() - 2) : null;

        Double changeAmount = null;
        Double changeRate = null;
        String changeLabel = "—";
        // 단위 배수가 다르면 비교 자체가 성립하지 않는다(100엔당 값과 1엔당 값을 빼는 꼴).
        // 배수는 통화별로 고정이라 정상 경로에서는 어긋나지 않지만, 어긋난 채 계산하면 등락이
        // 수천 %로 튀면서도 예외는 안 나므로 여기서 막는다.
        boolean comparable = previous != null
                && previous.getDealBaseRate() != 0
                && previous.getUnitMultiplier() == current.getUnitMultiplier();
        if (comparable) {
            double diff = current.getDealBaseRate() - previous.getDealBaseRate();
            double percent = diff / previous.getDealBaseRate() * 100.0;
            changeAmount = round(diff, 2);
            changeRate = round(percent, 2);
            String arrow = diff > 0 ? "▲" : diff < 0 ? "▼" : "—";
            changeLabel = String.format(Locale.KOREA, "%s %.2f%%", arrow, Math.abs(percent));
        }

        return new ExchangeRateDto.ExchangeRateItem(
                current.getCurrencyCode(), current.getCurrencyName(), current.getUnitMultiplier(),
                label(current), current.getDealBaseRate(), changeAmount, changeRate, changeLabel,
                current.getRateSource(),
                ExchangeRatePoint.SOURCE_CROSS_USD.equals(current.getRateSource()));
    }

    /** "USD/KRW", 100단위 고시 통화는 "JPY(100)/KRW". 프론트가 표기 규칙을 다시 짜지 않게 한다. */
    private static String label(ExchangeRatePoint point) {
        return point.getUnitMultiplier() == 1
                ? point.getCurrencyCode() + "/KRW"
                : point.getCurrencyCode() + "(" + point.getUnitMultiplier() + ")/KRW";
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    /** 노출 대상 통화. 순서를 보존해야 화면 노출 순서가 고정된다. */
    private List<String> targetCurrencies() {
        return splitCodes(currencies);
    }

    private static List<String> splitCodes(String configured) {
        return Arrays.stream(configured.split(","))
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .toList();
    }
}

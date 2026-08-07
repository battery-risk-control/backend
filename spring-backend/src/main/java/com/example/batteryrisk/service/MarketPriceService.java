package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.MaterialPricePoint;
import com.example.batteryrisk.dto.MarketPriceDto;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.MaterialPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 원자재 가격 추이(공개 대시보드 패널)의 수집·저장·조회를 담당한다.
 *
 * <p><b>수집 경로:</b> Spring 스케줄러 → FastAPI {@code /internal/market/prices}(yfinance) →
 * {@code material_price_points} upsert. FastAPI는 PostgreSQL에 접근하지 않으므로 저장은 여기서 한다.
 * yfinance는 API 키가 필요 없는 공개 데이터라 <b>호출 비용이 없다</b> — 뉴스 수집의 LLM 추출과 달리
 * 별도 비용 가드를 두지 않는 이유다.
 *
 * <p><b>갱신 시각:</b> 미국 증시 종가는 한국 시간 이른 아침에 확정되므로 07:00(KST)에 돈다.
 * 자정에 돌리면 미국 장중이라 당일 종가가 없다. 매번 넉넉한 구간을 다시 받아 upsert하므로
 * 며칠 꺼져 있다가 켜져도 빠진 구간이 자동으로 메워진다.
 */
@Service
public class MarketPriceService {
    private static final Logger log = LoggerFactory.getLogger(MarketPriceService.class);

    /** 한 번에 다시 받아오는 구간. 넉넉히 잡아야 스케줄러가 쉰 기간이 자동으로 메워진다. */
    private static final int REFRESH_DAYS = 180;

    /**
     * 15분 갱신이 훑는 구간(일).
     *
     * <p><b>여기를 {@link #REFRESH_DAYS}로 두면 안 된다.</b> 15분마다 180일치를 다시 받으면
     * 하루에 7종목 × 96회 × 약 125행 = 8만 건 넘는 upsert가 도는데, 그중 실제로 값이 바뀌는
     * 것은 <b>오늘 행 7건뿐</b>이다(과거 종가는 확정돼 다시 와도 같은 값이다). 최근 며칠만
     * 받아 오늘 행을 갱신하고, 빠진 과거 구간은 매일 07:00 전체 갱신이 메운다.
     *
     * <p>5일인 이유는 주말·공휴일이 끼어도 직전 거래일이 최소 하나는 들어오게 하기 위해서다.
     */
    private static final int INTRADAY_REFRESH_DAYS = 5;

    /** 조회 구간 기본값. 프론트 기본 선택 탭("1개월")에 맞춘다. */
    private static final int DEFAULT_TREND_DAYS = 30;

    /** "1일" 탭 시간별 라벨의 KST 환산 포맷(yyyy-MM-dd'T'HH:mm). 프론트가 문자열 정렬 후 HH:mm만 표시한다. */
    private static final java.time.format.DateTimeFormatter KST_MINUTE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * 조회 구간 상한. {@link #REFRESH_DAYS}보다 길게 요청해봐야 저장된 데이터가 없어 빈 구간만
     * 늘어나므로 수집 구간에 맞춰 자른다.
     */
    private static final int MAX_TREND_DAYS = REFRESH_DAYS;

    /** 연율화 계수(연간 거래일 √252). 일간 변동성을 연율화 변동성(%)으로 바꿔 리스크 지수로 쓴다. */
    private static final double ANNUALIZATION = Math.sqrt(252);

    private static final int RISK_CRITICAL_MIN = 70;
    private static final int RISK_WARNING_MIN = 40;

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final RestClient fastApiRestClient;
    private final MaterialPriceRepository priceRepository;
    private final ErpRepository erpRepository;

    /** 자동 갱신 on/off. 비용이 없어 기본 true지만, 오프라인 시연 등에서 끌 수 있게 열어둔다. */
    @Value("${app.market-price.scheduler-enabled:true}")
    private boolean schedulerEnabled;

    /**
     * 15분 장중 갱신 on/off. 매일 07:00 전체 갱신과 <b>따로</b> 끌 수 있게 나눠 둔다 —
     * yfinance는 공식 API가 아니라 Yahoo가 언제든 호출 빈도를 조일 수 있는데, 그때
     * 전체 갱신까지 같이 멈추면 차트가 통째로 낡는다. 이것만 끄면 하루 1회 갱신으로 돌아간다.
     */
    @Value("${app.market-price.intraday-enabled:true}")
    private boolean intradayEnabled;

    public MarketPriceService(
            RestClient fastApiRestClient,
            MaterialPriceRepository priceRepository,
            ErpRepository erpRepository) {
        this.fastApiRestClient = fastApiRestClient;
        this.priceRepository = priceRepository;
        this.erpRepository = erpRepository;
    }

    /** 매일 07:00(KST). 미국 증시 마감 이후라 전 거래일 종가가 확정돼 있다. */
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    public void refreshDaily() {
        if (!schedulerEnabled) {
            log.debug("가격 자동 갱신 비활성(app.market-price.scheduler-enabled=false) — 건너뜀");
            return;
        }
        refresh(REFRESH_DAYS);
    }

    /**
     * 15분마다 최근 거래일만 다시 받아 <b>오늘 행</b>을 갱신한다(장중 갱신).
     *
     * <p>yfinance의 일봉은 장중에도 <b>당일 미완성 봉</b>을 현재가로 채워 돌려주므로, 짧은
     * 구간을 자주 받으면 차트 마지막 점이 실제로 움직인다. 종목이 미국(ALB·BHP·GLNCY·AA·FCX)과
     * 호주(S32.AX·SYR.AX)에 걸쳐 있어 대략 KST 09:00~15:00(ASX)과 22:30~05:00(미국)에 값이
     * 변한다. 그 밖의 시간대에는 같은 값을 다시 써서 사실상 무동작이다 — 장 시간 판정을
     * 넣지 않은 것은 두 거래소의 휴장일·서머타임을 각각 관리해야 해서 얻는 것보다 부담이 크고,
     * 구간이 5일뿐이라 헛돌아도 비용이 거의 없기 때문이다.
     *
     * <p>호출량은 종목 7개 × 하루 96회 = 약 672회다. yfinance는 인증키가 없는 비공식
     * 스크래핑 경로라 Yahoo가 언제든 조일 수 있으므로, 막혔을 때를 전제로 설계돼 있다 —
     * FastAPI가 종목 단위로 예외를 격리하고(`fetch_prices`), 실패해도 DB의 직전 값이 남아
     * 화면이 비지 않는다. 빈도를 낮추려면 {@code app.market-price.intraday-cron}을 늘리거나
     * {@code app.market-price.intraday-enabled=false}로 끄면 하루 1회로 돌아간다.
     */
    @Scheduled(cron = "${app.market-price.intraday-cron:0 */15 * * * *}", zone = "Asia/Seoul")
    public void refreshIntraday() {
        if (!schedulerEnabled || !intradayEnabled) {
            log.debug("가격 장중 갱신 비활성 — 건너뜀");
            return;
        }
        refresh(INTRADAY_REFRESH_DAYS);
    }

    /**
     * 최초 기동 시 데이터가 하나도 없으면 즉시 한 번 채운다 — 그러지 않으면 다음 07:00까지 패널이 빈다.
     * 이미 데이터가 있으면 아무 것도 하지 않으므로 재기동이 잦아도 부담이 없다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnFirstBoot() {
        if (!schedulerEnabled || priceRepository.count() > 0) {
            return;
        }
        log.info("가격 데이터가 비어 있어 최초 적재를 시작합니다.");
        refresh();
    }

    /** 전체 구간({@link #REFRESH_DAYS})을 다시 받는다. 기동 훅과 수동 호출이 쓴다. */
    @Transactional
    public MarketPriceDto.RefreshResult refresh() {
        return refresh(REFRESH_DAYS);
    }

    /**
     * FastAPI에서 최근 {@code days}일 가격을 받아 저장한다. 실패해도 예외를 밖으로 던지지 않는다 —
     * 스케줄러/기동 훅에서 호출하므로 여기서 터지면 애플리케이션 상태에 영향을 준다.
     *
     * <p>구간이 짧아도 저장 방식은 같다 — 복합키(자재, 거래일) upsert라 겹치는 날짜는 갱신되고
     * 받지 않은 과거 날짜는 그대로 남는다. 그래서 15분 갱신이 5일치만 받아도 과거 차트가 잘리지 않는다.
     */
    @Transactional
    public MarketPriceDto.RefreshResult refresh(int days) {
        // 조회부터 저장까지를 한 번에 감싼다 — 스케줄러와 기동 훅에서 호출되므로 어느 단계에서
        // 터지든 밖으로 나가면 안 된다(기동 훅에서 예외가 나가면 애플리케이션 기동 자체가 실패한다).
        try {
            MarketPriceDto.FastApiPriceResponse response = fastApiRestClient.post()
                    .uri("/api/v1/internal/market/prices")
                    .body(new MarketPriceDto.FastApiPriceRequest(days))
                    .retrieve()
                    .body(MarketPriceDto.FastApiPriceResponse.class);
            if (response == null || !response.success() || response.data() == null
                    || response.data().points() == null) {
                throw new IllegalStateException("FastAPI 가격 응답이 올바르지 않습니다.");
            }

            List<MaterialPricePoint> entities = response.data().points().stream()
                    .map(point -> MaterialPricePoint.of(
                            point.materialCategory(), LocalDate.parse(point.priceDate()),
                            point.closePrice(), point.stockVol20d(), point.ticker()))
                    .toList();
            // 장중 갱신(5일 창)은 20일 변동성을 계산할 수 없어 전부 null로 온다. 그 null을 그대로
            // 저장하면 매일 07:00 전체 갱신이 계산해 둔 최근 며칠의 변동성을 지워, 최신 구간(특히
            // "1일")의 리스크가 0으로 죽는다. 새 값이 null일 때는 기존에 저장된 변동성을 보존한다.
            for (MaterialPricePoint incoming : entities) {
                if (incoming.getStockVol20d() == null) {
                    priceRepository.findById(new MaterialPricePoint.Key(
                                    incoming.getMaterialCategory(), incoming.getPriceDate()))
                            .map(MaterialPricePoint::getStockVol20d)
                            .ifPresent(incoming::setStockVol20d);
                }
            }
            // 복합키(자재, 거래일)라 이미 있는 날짜는 갱신, 없으면 삽입으로 수렴한다.
            priceRepository.saveAll(entities);

            List<String> failed = response.data().failedTickers() == null
                    ? List.of() : response.data().failedTickers();
            log.info("원자재 가격 갱신 완료(최근 {}일): {}건 저장, 실패 종목 {}",
                    days, entities.size(), failed.isEmpty() ? "없음" : failed);
            return new MarketPriceDto.RefreshResult(entities.size(), failed, "SUCCESS", null);
        } catch (RuntimeException exception) {
            log.warn("원자재 가격 갱신 실패: {}", exception.toString());
            return new MarketPriceDto.RefreshResult(0, List.of(), "FAILED", exception.getMessage());
        }
    }

    /**
     * 공개 가격 추이. 자재별로 <b>6개월({@value #REFRESH_DAYS}일) 창 첫 거래일</b>을 100으로 둔
     * 지수를 만든다 — 종목마다 주가 절대값이 달라(예: BHP 60달러대, ALB 130달러대) 원값 그대로는
     * 한 차트에 겹쳐 그릴 수 없다.
     *
     * <p><b>일별 탭(1주~6개월)은 기준일을 6개월 창 첫 거래일로 고정한다.</b> 예전에는 기준일이
     * 조회 구간에 종속돼(7일이면 7일 전, 30일이면 30일 전) 같은 거래일이라도 탭마다 지수 값이
     * 달랐다. 이제는 어느 탭에서 보든 같은 날짜의 지수가 같은 값이 되고, 짧은 탭에서는 왼쪽 끝이
     * 100이 아니라 "6개월 전 대비 현재 수준"에서 시작한다("구간 시작 대비 몇 %"는 요약 카드의
     * {@code change_label}이 계속 담당한다). 6개월치를 한 번만 조회해 앵커를 잡고, 요청 구간은
     * 메모리에서 잘라 쓰므로 쿼리가 늘지 않는다.
     *
     * <p><b>"1일" 탭만 별도 기준이다.</b> 하루 등락은 6개월 스케일에서 거의 평평해 보이지 않으므로,
     * 인트라데이는 예외적으로 당일 첫 봉(시가)을 100으로 둔다({@link #intradayTrends()}). 기준이
     * 다르다는 안내는 프론트가 "1일" 탭에서만 노출한다.
     *
     * @param days 조회 구간(일). 1~{@value #MAX_TREND_DAYS}로 자른다. days==1은 "1일" 탭으로,
     *             일봉이 아니라 최근 1거래일 <b>시간별 흐름</b>을 반환한다({@link #intradayTrends()}).
     */
    public List<MarketPriceDto.PriceSeries> priceTrends(int days) {
        if (days == 1) {
            return intradayTrends();
        }
        Map<String, List<MarketPriceDto.SourcingCountry>> sourcing = sourcingCountriesByMaterial();
        LocalDate windowFrom = LocalDate.now(SERVICE_ZONE).minusDays(clampDays(days));
        return groupByMaterial(REFRESH_DAYS).entrySet().stream()
                .map(entry -> {
                    List<MaterialPricePoint> full = entry.getValue();
                    // 앵커 = 6개월 창 첫 거래일 종가. 요청 구간이 짧아도 이 기준으로 나눈다.
                    double base = full.get(0).getClosePrice();
                    List<MaterialPricePoint> window = full.stream()
                            .filter(point -> !point.getPriceDate().isBefore(windowFrom))
                            .toList();
                    return new MarketPriceDto.PriceSeries(
                            RiskEventService.MATERIAL_NAME_KO.getOrDefault(entry.getKey(), entry.getKey()),
                            "지수(6개월 전=100)",
                            full.get(0).getPriceDate().toString(),
                            sourcing.getOrDefault(entry.getKey(), List.of()),
                            toIndexedPoints(window, base));
                })
                .toList();
    }

    /**
     * 자재 대분류 → 조달국 목록. 국가 목록을 응답에 실어 보내면 프론트가 필터를 클라이언트에서
     * 처리할 수 있어 탭 전환마다 재조회할 필요가 없고, 드롭다운 항목도 응답에서 자동 생성된다
     * (지금 프론트는 5개국이 하드코딩돼 있어 실제 조달국 15개국과 어긋나 있다).
     *
     * <p>자재가 8종뿐이라 매 요청 조회해도 부담이 없다 — 조달 관계는 자주 바뀌지 않지만,
     * 캐시를 두면 계약 업로드로 공급사가 늘었을 때 화면이 낡은 목록을 계속 보여준다.
     */
    private Map<String, List<MarketPriceDto.SourcingCountry>> sourcingCountriesByMaterial() {
        Map<String, List<MarketPriceDto.SourcingCountry>> grouped = new LinkedHashMap<>();
        for (ErpRepository.MaterialCountryRow row : erpRepository.findMaterialSourcingCountries()) {
            grouped.computeIfAbsent(row.materialCategory(), key -> new ArrayList<>())
                    .add(new MarketPriceDto.SourcingCountry(
                            row.countryCode(), RiskEventService.countryNameKo(row.countryCode())));
        }
        return grouped;
    }

    /**
     * 공개 요약 카드. 가격 추이와 <b>같은 구간·같은 데이터</b>에서 파생하므로 차트와 어긋나지 않는다.
     *
     * <p>risk_score는 연율화 변동성(일간 변동성 × √252)을 백분율로 환산해 0~100으로 자른 값이다.
     * 자재별 리스크 판정을 별도로 정의하기 전까지의 대용이며, 임의 수치가 아니라 표준 지표를 쓴다.
     */
    public List<MarketPriceDto.PriceSummary> priceSummaries(int days) {
        if (days == 1) {
            return intradaySummaries();
        }
        return groupByMaterial(days).entrySet().stream()
                .map(entry -> {
                    List<MaterialPricePoint> points = entry.getValue();
                    int riskScore = riskScore(points);
                    return new MarketPriceDto.PriceSummary(
                            RiskEventService.MATERIAL_NAME_KO.getOrDefault(entry.getKey(), entry.getKey()),
                            changeLabel(points), riskScore, grade(riskScore));
                })
                .toList();
    }

    /** 조회 구간의 가격을 자재별로 묶는다(리포지토리가 자재·날짜 오름차순으로 반환). */
    private Map<String, List<MaterialPricePoint>> groupByMaterial(int days) {
        LocalDate from = LocalDate.now(SERVICE_ZONE).minusDays(clampDays(days));
        Map<String, List<MaterialPricePoint>> grouped = new LinkedHashMap<>();
        for (MaterialPricePoint point :
                priceRepository.findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(from)) {
            grouped.computeIfAbsent(point.getMaterialCategory(), key -> new ArrayList<>()).add(point);
        }
        return grouped;
    }

    // ── "1일" 탭: 시간별 장중 흐름 ─────────────────────────────────────────
    // 일봉 1점으로는 하루 흐름을 못 그려(100/0%/0), FastAPI에서 최근 1거래일 시간별(1h) 시세를
    // 실시간으로 받아 시간축 차트를 만든다. 거래소가 미국·호주로 섞여 시각이 제각각이라 KST로
    // 환산해 한 축에 정렬한다(프론트는 KST 시각 문자열을 그대로 정렬·표시). 저장은 하지 않는다.

    /**
     * 최근 1거래일 시간별 지수 추이(자재별 첫 봉=100).
     *
     * <p>일별 탭과 달리 <b>당일 첫 봉(시가)을 100</b>으로 둔다 — 하루 등락은 6개월 앵커 스케일에서
     * 거의 평평해 시간별 흐름이 안 보이기 때문이다. 기준이 다르므로 단위 라벨도 구분하고, 프론트가
     * "1일" 탭에서만 별도 기준 안내를 띄운다.
     */
    public List<MarketPriceDto.PriceSeries> intradayTrends() {
        Map<String, List<MarketPriceDto.SourcingCountry>> sourcing = sourcingCountriesByMaterial();
        return fetchIntradayByMaterial().entrySet().stream()
                .map(entry -> new MarketPriceDto.PriceSeries(
                        RiskEventService.MATERIAL_NAME_KO.getOrDefault(entry.getKey(), entry.getKey()),
                        "지수(당일 시가=100)",
                        toKstLabel(entry.getValue().get(0).priceDate()),
                        sourcing.getOrDefault(entry.getKey(), List.of()),
                        toIntradayIndexedPoints(entry.getValue())))
                .toList();
    }

    /**
     * 최근 1거래일 시간별 요약 카드. 등락은 <b>당일 첫 봉→마지막 봉</b>으로 계산하고, 리스크 지수는
     * 시간 단위로 정의가 없어 <b>저장된 일간 변동성</b>을 그대로 쓴다(값의 성격을 탭마다 바꾸지 않는다).
     */
    public List<MarketPriceDto.PriceSummary> intradaySummaries() {
        Map<String, List<MaterialPricePoint>> daily = groupByMaterial(30);
        return fetchIntradayByMaterial().entrySet().stream()
                .map(entry -> {
                    List<MarketPriceDto.FastApiPricePoint> points = entry.getValue();
                    int riskScore = riskScore(daily.getOrDefault(entry.getKey(), List.of()));
                    return new MarketPriceDto.PriceSummary(
                            RiskEventService.MATERIAL_NAME_KO.getOrDefault(entry.getKey(), entry.getKey()),
                            formatChange(points.get(0).closePrice(),
                                    points.get(points.size() - 1).closePrice()),
                            riskScore, grade(riskScore));
                })
                .toList();
    }

    /** FastAPI 시간별 조회 결과를 자재별로 묶는다(응답이 자재·시각 순이라 그대로 순서 유지). */
    private Map<String, List<MarketPriceDto.FastApiPricePoint>> fetchIntradayByMaterial() {
        MarketPriceDto.FastApiPriceResponse response = fastApiRestClient.post()
                .uri("/api/v1/internal/market/intraday")
                .retrieve()
                .body(MarketPriceDto.FastApiPriceResponse.class);
        if (response == null || !response.success() || response.data() == null
                || response.data().points() == null) {
            throw new IllegalStateException("FastAPI 장중 가격 응답이 올바르지 않습니다.");
        }
        Map<String, List<MarketPriceDto.FastApiPricePoint>> grouped = new LinkedHashMap<>();
        for (MarketPriceDto.FastApiPricePoint point : response.data().points()) {
            grouped.computeIfAbsent(point.materialCategory(), key -> new ArrayList<>()).add(point);
        }
        return grouped;
    }

    /** 시간별 지수 포인트. 날짜(date)에는 KST 시각 문자열(yyyy-MM-dd'T'HH:mm)을 담아 프론트가 정렬·표시한다. */
    private static List<MarketPriceDto.PricePoint> toIntradayIndexedPoints(
            List<MarketPriceDto.FastApiPricePoint> points) {
        double base = points.get(0).closePrice();
        Instant now = Instant.now();
        return points.stream()
                .map(point -> new MarketPriceDto.PricePoint(
                        toKstLabel(point.priceDate()),
                        base == 0 ? 100.0 : Math.round(point.closePrice() / base * 1000.0) / 10.0,
                        now))
                .toList();
    }

    /** tz-aware ISO(예: 2026-08-07T09:00:00-04:00)를 KST 시각 문자열로 환산한다. */
    private static String toKstLabel(String isoTimestamp) {
        try {
            return OffsetDateTime.parse(isoTimestamp)
                    .atZoneSameInstant(SERVICE_ZONE)
                    .format(KST_MINUTE);
        } catch (java.time.format.DateTimeParseException exception) {
            // 오프셋이 없는 형식이면 그대로 둔다 — 프론트가 문자열 정렬만 하므로 깨지지 않는다.
            return isoTimestamp;
        }
    }

    /**
     * 구간을 유효 범위로 자른다. 잘못된 값에 400을 던지지 않고 조용히 맞추는 것은 뉴스 속보의
     * limit 처리와 같은 방침이다 — 공개 화면이라 파라미터 하나 때문에 패널이 통째로 비면 안 된다.
     */
    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, MAX_TREND_DAYS));
    }

    private static List<MarketPriceDto.PricePoint> toIndexedPoints(List<MaterialPricePoint> points, double base) {
        return points.stream()
                .map(point -> new MarketPriceDto.PricePoint(
                        point.getPriceDate().toString(),
                        base == 0 ? 100.0 : Math.round(point.getClosePrice() / base * 1000.0) / 10.0,
                        point.getUpdatedAt()))
                .toList();
    }

    /** 구간 첫 거래일 대비 등락. 프론트 mock과 같은 표기(▲/▼ + 소수 1자리 %)를 유지한다. */
    private static String changeLabel(List<MaterialPricePoint> points) {
        return formatChange(points.get(0).getClosePrice(), points.get(points.size() - 1).getClosePrice());
    }

    /** 첫 값 대비 마지막 값의 등락 표기(▲/▼ + 소수 1자리 %). 일봉·시간별 요약이 함께 쓴다. */
    private static String formatChange(double first, double last) {
        if (first == 0) {
            return "— 0.0%";
        }
        double changePercent = (last - first) / first * 100.0;
        String arrow = changePercent > 0 ? "▲" : changePercent < 0 ? "▼" : "—";
        return String.format(Locale.KOREA, "%s %.1f%%", arrow, Math.abs(changePercent));
    }

    /** 가장 최근 거래일의 20일 변동성을 연율화 백분율로 환산. 변동성이 아직 없으면 0으로 둔다. */
    private static int riskScore(List<MaterialPricePoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            Double volatility = points.get(i).getStockVol20d();
            if (volatility != null) {
                return (int) Math.min(100, Math.round(volatility * ANNUALIZATION * 100));
            }
        }
        return 0;
    }

    private static String grade(int riskScore) {
        if (riskScore >= RISK_CRITICAL_MIN) return "심각";
        if (riskScore >= RISK_WARNING_MIN) return "주의";
        return "정상";
    }
}

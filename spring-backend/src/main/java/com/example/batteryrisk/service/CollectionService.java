package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.CollectionCursor;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.AnalysisDto;
import com.example.batteryrisk.dto.CollectionDto;
import com.example.batteryrisk.dto.ExtractionDto;
import com.example.batteryrisk.repository.CollectionCursorRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** F4: 데이터 소스별 Adapter를 실행해 원본을 저장하고, 신규 뉴스에 대해 F3 분석을 트리거합니다. */
@Service
public class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    /**
     * F3(LLM) 분석을 태우기 전에 본문에 핵심광물 이름이 실제로 있는지 코드로 먼저 거른다.
     *
     * <p>도입 배경(2026-08-07): GDELT 트리아지(XGBoost, recall 우선)는 통과율이 60~70%로
     * 널널해서 15분 구간에 700~1000건씩 통과하는데, 크롤링 예산(FastAPI
     * {@code MAX_CRAWL_PER_RUN}=30)에 걸려 그중 30건만(파일 순서, 사실상 무작위) 실제로
     * 크롤링됐다 — 진짜 관련 기사가 31번째 이후에 있으면 그 사이클에서 영영 못 봤다(실측:
     * TSMC 기사가 이 방식으로 유실됨). 크롤링 예산 자체를 늘리면 크롤링된 것마다 F3 LLM 호출이
     * 뒤따라 붙어 15분마다 수백~천 건씩 LLM을 호출하게 되고, OpenAI 일일 요청한도(RPD, 롤링
     * 24시간)를 순식간에 소진한다(2026-08-06~07 실측).
     *
     * <p>해결: 크롤링 예산은 그대로 두되(FastAPI 쪽 수정 없음), <b>Spring이 이미 크롤링된 본문을
     * 갖고 있으므로</b> LLM을 부르기 전에 여기서 한 번 더 걸러 "핵심광물 이름이 문자 그대로
     * 없으면 LLM도 물어볼 필요 없이 무관"이라는 오늘 고친 프롬프트의 [HARD CONSTRAINT] 규칙을
     * 코드에서 무료로 선반영한다 — LLM이 어차피 False를 낼 게 뻔한 호출을 아예 안 보낸다.
     * 단어경계(정규식 {@code \b})를 넣는 이유는 {@link RiskEventService}의
     * {@code materialKeywordPattern()}과 같다("copper pillars"처럼 단어 자체가 무관한
     * 문맥에서 등장하는 경우까지는 못 막지만, 합성어에 파묻힌 글자열은 막는다) — SQL
     * {@code \y} 대신 Java 정규식 {@code \b}를 쓰는 것만 다르고 키워드 목록은 그쪽과 동일하다.
     */
    private static final Pattern MATERIAL_KEYWORD_PATTERN = Pattern.compile(
            "\\b(" + String.join("|",
                    RiskEventService.MATERIAL_KEYWORDS.values().stream()
                            .flatMap(List::stream)
                            .toList())
                    + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * XGBoost 트리아지의 {@code CORE_PRODUCER_WHITELIST}(triage_filter.py)와 같은 FIPS 10-4
     * 국가코드 목록을 여기 다시 둔다(다른 언어/저장소라 직접 참조 불가, 값만 그대로 복제).
     *
     * <p>{@code mentionsCoreMaterial()}의 키워드 목록이 영어·한국어뿐이라, 이 화이트리스트가
     * 없으면 칠레·아르헨티나(리튬)·브라질 인접국 등에서 스페인어·포르투갈어로 보도된 진짜
     * 관련 기사가 "키워드 없음"으로 LLM 호출 자체를 안 받고 조용히 걸러진다 — 이건 원래
     * 없던 회귀다(이 필터 도입 전에는 크롤링된 기사는 언어 무관하게 전부 LLM(다국어 가능)에게
     * 넘어갔다). 트리아지가 이미 같은 이유로 "핵심 생산국은 모델 점수와 무관하게 항상 통과"
     * 원칙을 쓰고 있으므로, 여기서도 동일하게 핵심 생산국이면 키워드 매칭 여부와 무관하게
     * 항상 LLM으로 넘긴다.
     */
    private static final Set<String> CORE_PRODUCER_COUNTRY_CODES = Set.of(
            "ID", // 니켈(인도네시아)
            "CG", // 코발트(DRC)
            "CI", // 리튬(칠레)
            "AR", // 리튬(아르헨티나)
            "AS", // 리튬/니켈(호주)
            "CH", // 흑연(중국)
            "SF"  // 망간(남아프리카공화국)
    );

    private static boolean mentionsCoreMaterial(RawEvent event) {
        if (event.getCountryCode() != null && CORE_PRODUCER_COUNTRY_CODES.contains(event.getCountryCode())) {
            return true;
        }
        String text = (event.getTitle() == null ? "" : event.getTitle())
                + " " + (event.getContent() == null ? "" : event.getContent());
        return MATERIAL_KEYWORD_PATTERN.matcher(text).find();
    }

    private final List<DataSourceAdapter> adapters;
    private final RawEventRepository rawEventRepository;
    private final CollectionCursorRepository cursorRepository;
    private final AnalysisService analysisService;
    private final ExtractionClient extractionClient;
    private final HistoricalFeatureJoinService historicalFeatureJoinService;

    public CollectionService(
            List<DataSourceAdapter> adapters, RawEventRepository rawEventRepository,
            CollectionCursorRepository cursorRepository, AnalysisService analysisService,
            ExtractionClient extractionClient, HistoricalFeatureJoinService historicalFeatureJoinService) {
        this.adapters = adapters;
        this.rawEventRepository = rawEventRepository;
        this.cursorRepository = cursorRepository;
        this.analysisService = analysisService;
        this.extractionClient = extractionClient;
        this.historicalFeatureJoinService = historicalFeatureJoinService;
    }

    /**
     * 자동 수집 스케줄러 on/off. 기본 false — 자동 수집→F3 분석→LLM 추출(OpenAI) 사고를 막기 위함.
     * 켜려면 COLLECTION_SCHEDULER_ENABLED=true (app.collection.scheduler-enabled=true).
     * 수동 트리거(runAll/runSource, CollectionController)는 이 플래그와 무관하게 항상 동작한다.
     */
    @Value("${app.collection.scheduler-enabled:false}")
    private boolean schedulerEnabled;

    /**
     * 수집된 뉴스에 대한 F3 분석 자동 트리거 on/off. <b>기본 false</b> — 비용이 나가는 쪽을 opt-in으로 둔다.
     *
     * <p>false면 raw_events 저장까지만 하고 LLM 추출·분석을 건너뛴다 — 공개 뉴스 속보 패널처럼
     * 뉴스 원본(제목·출처·시각)만 필요한 경우 OpenAI 호출 비용 없이 수집할 수 있다.
     * GDELT 수집·트리아지 자체는 로컬 XGBoost와 공개 파일만 쓰므로 이 경로에는 비용이 없다.
     *
     * <p>기본값이 false인 이유: 스케줄러를 꺼두어도 수동 {@code POST /collection/run} 한 번이 수집된
     * 뉴스 건수만큼 LLM 호출을 발생시킨다(실제로 그렇게 30건이 호출된 적 있음). 켜는 걸 깜빡하면
     * 분석이 안 생길 뿐 재실행하면 되지만, 끄는 걸 깜빡하면 실제 비용이 나가고 되돌릴 수 없다.
     *
     * <p>이 기본값은 application.yml·docker-compose.yml과 같은 값이어야 한다. 세 군데가 갈라지면
     * 설정 파일에서 껐다고 믿는 상태로 여기 기본값이 살아나 가드가 조용히 무력화된다.
     *
     * <p>수동 검증용 {@link #triggerTestNews}는 이 플래그의 영향을 받지 않는다 — 호출 자체가
     * "이 뉴스를 분석해보라"는 명시적 의사표시이기 때문이다. 따라서 기본 off여도 분석 테스트는 가능하다.
     */
    @Value("${app.collection.analysis-enabled:false}")
    private boolean analysisEnabled;

    /** Fast Track: GDELT 자체 갱신 주기(15분)에 맞춰 뉴스·재난을 폴링합니다. (schedulerEnabled=true일 때만 실행) */
    @Scheduled(fixedRate = 900_000, initialDelay = 60_000)
    public void runFastTrack() {
        if (!schedulerEnabled) {
            log.debug("자동 수집 스케줄러 비활성(app.collection.scheduler-enabled=false) — 건너뜀");
            return;
        }
        runSource("GDELT");
        runSource("GDACS");
    }

    public CollectionDto.CollectionSummary runAll() {
        return new CollectionDto.CollectionSummary(adapters.stream().map(a -> runSource(a.sourceName())).toList());
    }

    @Transactional
    public CollectionDto.CollectionRunResult runSource(String sourceName) {
        DataSourceAdapter adapter = adapters.stream()
                .filter(a -> a.sourceName().equals(sourceName))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return new CollectionDto.CollectionRunResult(sourceName, null, 0, 0, 0, "UNKNOWN_SOURCE", null);
        }

        CollectionCursor cursor = cursorRepository.findById(sourceName)
                .orElseGet(() -> new CollectionCursor(sourceName));

        List<CollectionDto.CollectedItem> items;
        try {
            items = adapter.collect(cursor.getCursorValue());
        } catch (RuntimeException exception) {
            log.warn("{} 수집 중 예외 발생: {}", sourceName, exception.getMessage());
            return new CollectionDto.CollectionRunResult(
                    sourceName, adapter.dataType(), 0, 0, 0, "FAILED", exception.getMessage());
        }

        int newItems = 0;
        int analysesTriggered = 0;
        boolean isEventLike = "NEWS".equals(adapter.dataType()) || "DISASTER".equals(adapter.dataType());
        if (!analysisEnabled && "NEWS".equals(adapter.dataType())) {
            log.info("{} 수집: 분석 자동 트리거 비활성(app.collection.analysis-enabled=false) — 원본만 저장합니다.", sourceName);
        }

        for (CollectionDto.CollectedItem item : items) {
            if (isEventLike && item.externalId() != null
                    && rawEventRepository.existsBySourceAndExternalId(sourceName, item.externalId())) {
                continue;
            }
            String contentHash = sha256((item.title() == null ? "" : item.title()) + "|" + item.content());
            // GDELT는 같은 기사(같은 URL)를 행위자 쌍마다 다른 GlobalEventID로 여러 번 보고해서
            // externalId 중복 체크만으론 안 걸러진다 — 제목+본문이 같으면 content_hash도 같으므로
            // 이걸로 한 번 더 걸러 같은 기사를 재크롤링·재분석(=중복 LLM 호출)하지 않게 한다.
            if (isEventLike && rawEventRepository.existsBySourceAndContentHash(sourceName, contentHash)) {
                continue;
            }
            RawEvent rawEvent = RawEvent.of(
                    sourceName, adapter.dataType(), item.externalId(), contentHash,
                    item.title(), item.content(), item.sourceUrl(), item.countryCode(),
                    item.goldsteinScale(), item.payloadJson());
            rawEventRepository.saveAndFlush(rawEvent);
            newItems++;

            // title==null은 트리아지 통과 기사가 0건인 구간의 커서 전진용 sentinel(GdeltRealtimeTriageAdapter)
            // 이므로 분석 트리거 대상이 아니다. 이 체크가 없으면 sentinel 최초 발생 시 title/content가
            // null인 채로 FastAPI 추출(422)·Analysis 저장(event_title NOT NULL 위반)이 조용히 실패한다.
            //
            // mentionsCoreMaterial()은 크롤링 예산을 늘려도(위 클래스 주석 참고) LLM 호출량이
            // 그만큼 같이 안 늘게 막는 무료 사전 필터다 — "NEWS"에만 적용한다(DISASTER는 광물
            // 키워드가 원래 안 붙는 별개 도메인이라 이 필터를 걸면 항상 걸러져버린다).
            boolean skippedNoKeyword = "NEWS".equals(adapter.dataType())
                    && rawEvent.getTitle() != null && !mentionsCoreMaterial(rawEvent);
            if (skippedNoKeyword) {
                log.debug("핵심광물 키워드 없음 — 분석 생략(저장만): externalId={}", item.externalId());
            }
            if (analysisEnabled && "NEWS".equals(adapter.dataType()) && rawEvent.getTitle() != null
                    && !skippedNoKeyword) {
                UUID analysisId = triggerAnalysis(rawEvent);
                if (analysisId != null) {
                    rawEvent.markTriggeredAnalysis(analysisId);
                    rawEventRepository.saveAndFlush(rawEvent);
                    analysesTriggered++;
                }
            }
        }

        cursor.markSuccess(items.isEmpty() ? null : items.get(items.size() - 1).newCursorValue());
        cursorRepository.saveAndFlush(cursor);

        return new CollectionDto.CollectionRunResult(
                sourceName, adapter.dataType(), items.size(), newItems, analysesTriggered, "SUCCESS", null);
    }

    /**
     * GDELT 실시간 수집이 막혀있을 때 F4→F3 파이프라인을 검증하기 위한 테스트 전용 메서드입니다.
     * runSource()가 실제 뉴스에 대해 하는 것과 완전히 같은 처리(raw_events 저장 → triggerAnalysis)를 거칩니다.
     */
    @Transactional
    public CollectionDto.TestNewsResult triggerTestNews(
            String title, String content, String countryCode, String sourceUrl, Double goldsteinScale) {
        String externalId = "TEST-" + UUID.randomUUID();
        String contentHash = sha256((title == null ? "" : title) + "|" + content);
        RawEvent rawEvent = RawEvent.of(
                "TEST_NEWS", "NEWS", externalId, contentHash,
                title, content, sourceUrl, countryCode, goldsteinScale, null);
        rawEventRepository.saveAndFlush(rawEvent);

        UUID analysisId = triggerAnalysis(rawEvent);
        if (analysisId != null) {
            rawEvent.markTriggeredAnalysis(analysisId);
            rawEventRepository.saveAndFlush(rawEvent);
        }

        return new CollectionDto.TestNewsResult(
                rawEvent.getId(), analysisId != null ? analysisId.toString() : null,
                analysisId != null ? "SUCCESS" : "ANALYSIS_TRIGGER_FAILED");
    }

    /**
     * 추출은 여기서 딱 한 번만 호출한다(feature_overrides의 material 파생용). 그 결과를
     * extraction_override로 /analyze에 그대로 실어 보내, FastAPI가 같은 뉴스를 다시
     * 추출하지 않도록 한다(중복 LLM 호출 제거).
     */
    private UUID triggerAnalysis(RawEvent rawEvent) {
        try {
            ExtractionDto.ExtractData extraction = extractionClient.extract(
                    rawEvent.getTitle(), rawEvent.getContent(), rawEvent.getCountryCode());
            AnalysisDto.FeatureOverrides overrides = buildFeatureOverrides(rawEvent, extraction);
            AnalysisDto.AnalysisResponse response = analysisService.create(new AnalysisDto.AnalyzeRequest(
                    null, null, rawEvent.getTitle(), rawEvent.getContent(),
                    rawEvent.getSource(), rawEvent.getCountryCode(), rawEvent.getSourceUrl(),
                    overrides, toExtractionOverride(extraction)));
            return UUID.fromString(response.analysisId());
        } catch (RuntimeException exception) {
            log.warn("수집된 뉴스에 대한 분석 트리거 실패: {}", exception.getMessage(), exception);
            return null;
        }
    }

    private AnalysisDto.FeatureOverrides buildFeatureOverrides(RawEvent rawEvent, ExtractionDto.ExtractData extraction) {
        String countryCode = rawEvent.getCountryCode();
        Instant eventTimestamp = rawEvent.getCollectedAt();

        String material = extraction != null && !extraction.affectedMaterials().isEmpty()
                ? extraction.affectedMaterials().get(0) : null;

        HistoricalFeatureJoinService.JoinResult joinResult =
                historicalFeatureJoinService.join(countryCode, eventTimestamp, material, rawEvent.getSourceUrl());
        long newsCount = historicalFeatureJoinService.countNewsOnSameDay(countryCode, eventTimestamp);
        Double bdiIndex = rawEventRepository.findFirstByDataTypeOrderByCollectedAtDesc("FREIGHT_INDEX")
                .map(event -> parseKeyValue(event.getContent(), "bdi_price", Double::parseDouble))
                .orElse(null);


        return new AnalysisDto.FeatureOverrides(
                rawEvent.getGoldsteinScale(), (int) newsCount,
                joinResult.gdacsAlertLevel(), joinResult.stockVolatility20d(), bdiIndex);
    }

    /** extraction이 null(FastAPI 호출 실패 등)이면 null을 반환 — analyze()가 자체적으로 재추출하도록 정상 폴백된다. */
    private AnalysisDto.ExtractionOverride toExtractionOverride(ExtractionDto.ExtractData extraction) {
        if (extraction == null) {
            return null;
        }
        return new AnalysisDto.ExtractionOverride(
                extraction.countryCode(), extraction.affectedMaterials(), extraction.eventType(),
                extraction.toneScore(), extraction.impactDomainDraft(), extraction.summaryKr(),
                extraction.isSupplyChainRelevant(), extraction.extractionModelVersion(), extraction.mock());
    }

    private <T> T parseKeyValue(String content, String key, Function<String, T> parser) {
        if (content == null) return null;
        for (String part : content.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) {
                try {
                    return parser.apply(kv[1].trim());
                } catch (RuntimeException exception) {
                    return null;
                }
            }
        }
        return null;
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

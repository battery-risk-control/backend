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

/** F4: 데이터 소스별 Adapter를 실행해 원본을 저장하고, 신규 뉴스에 대해 F3 분석을 트리거합니다. */
@Service
public class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private static final Set<String> MINING_HUB_COUNTRIES = Set.of("CL", "ID", "AU", "CD", "AR", "CN", "PH");

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

    /** Fast Track: 뉴스·재난은 30분 주기로 최신성이 중요합니다. (schedulerEnabled=true일 때만 실행) */
    @Scheduled(fixedRate = 1_800_000, initialDelay = 60_000)
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

        for (CollectionDto.CollectedItem item : items) {
            if (isEventLike && item.externalId() != null
                    && rawEventRepository.existsBySourceAndExternalId(sourceName, item.externalId())) {
                continue;
            }
            String contentHash = sha256((item.title() == null ? "" : item.title()) + "|" + item.content());
            RawEvent rawEvent = RawEvent.of(
                    sourceName, adapter.dataType(), item.externalId(), contentHash,
                    item.title(), item.content(), item.sourceUrl(), item.countryCode(), item.payloadJson());
            rawEventRepository.saveAndFlush(rawEvent);
            newItems++;

            if ("NEWS".equals(adapter.dataType())) {
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
            String title, String content, String countryCode, String sourceUrl) {
        String externalId = "TEST-" + UUID.randomUUID();
        String contentHash = sha256((title == null ? "" : title) + "|" + content);
        RawEvent rawEvent = RawEvent.of(
                "TEST_NEWS", "NEWS", externalId, contentHash,
                title, content, sourceUrl, countryCode, null);
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

    private UUID triggerAnalysis(RawEvent rawEvent) {
        try {
            AnalysisDto.FeatureOverrides overrides = buildFeatureOverrides(rawEvent);
            AnalysisDto.AnalysisResponse response = analysisService.create(new AnalysisDto.AnalyzeRequest(
                    null, null, rawEvent.getTitle(), rawEvent.getContent(),
                    rawEvent.getSource(), rawEvent.getCountryCode(), overrides));
            return UUID.fromString(response.analysisId());
        } catch (RuntimeException exception) {
            log.warn("수집된 뉴스에 대한 분석 트리거 실패: {}", exception.getMessage(), exception);
            return null;
        }
    }

    private AnalysisDto.FeatureOverrides buildFeatureOverrides(RawEvent rawEvent) {
        String countryCode = rawEvent.getCountryCode();
        Instant eventTimestamp = rawEvent.getCollectedAt();

        ExtractionDto.ExtractData extraction = extractionClient.extract(
                rawEvent.getTitle(), rawEvent.getContent(), countryCode);
        String material = extraction != null && !extraction.affectedMaterials().isEmpty()
                ? extraction.affectedMaterials().get(0) : null;

        HistoricalFeatureJoinService.JoinResult joinResult =
                historicalFeatureJoinService.join(countryCode, eventTimestamp, material, rawEvent.getSourceUrl());
        long newsCount = historicalFeatureJoinService.countNewsOnSameDay(countryCode, eventTimestamp);
        Boolean miningHub = countryCode != null ? MINING_HUB_COUNTRIES.contains(countryCode) : null;
        Double bdiIndex = rawEventRepository.findFirstByDataTypeOrderByCollectedAtDesc("FREIGHT_INDEX")
                .map(event -> parseKeyValue(event.getContent(), "bdi_price", Double::parseDouble))
                .orElse(null);

        return new AnalysisDto.FeatureOverrides(
                joinResult.goldsteinScale(), (int) newsCount, miningHub,
                joinResult.gdacsAlertLevel(), joinResult.stockVolatility20d(), bdiIndex);
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

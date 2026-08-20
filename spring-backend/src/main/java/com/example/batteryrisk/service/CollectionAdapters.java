package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.CollectionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** F4 외부 데이터 소스 하나를 표준화된 형태로 수집해오는 어댑터입니다. */
interface DataSourceAdapter {
    String sourceName();
    String dataType();
    List<CollectionDto.CollectedItem> collect(String cursorValue);
}

abstract class AbstractHttpAdapter {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final RestClient restClient;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractHttpAdapter(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(10_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}

/**
 * GDELT 실시간 조회→XGBoost 트리아지→크롤링을 FastAPI(팀원 realtime-pipeline 브랜치 포팅)에
 * 위임하는 어댑터입니다. 예전엔 이 클래스가 직접 정규식 키워드 매칭으로 크롤링 대상을 걸렀지만
 * (2026-07-24 GdeltNewsAdapter), 실측 검증된 XGBoost 트리아지 모델로 교체됐습니다(2026-07-27).
 * dedup·저장·F3 분석 트리거·커서 갱신은 CollectionService.runSource()가 기존 로직 그대로 처리합니다.
 */
@Component
class GdeltRealtimeTriageAdapter implements DataSourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(GdeltRealtimeTriageAdapter.class);

    private final RestClient fastApiRestClient;
    private final GdeltEventArchiveService gdeltEventArchiveService;

    GdeltRealtimeTriageAdapter(RestClient fastApiRestClient, GdeltEventArchiveService gdeltEventArchiveService) {
        this.fastApiRestClient = fastApiRestClient;
        this.gdeltEventArchiveService = gdeltEventArchiveService;
    }

    @Override
    public String sourceName() { return "GDELT"; }

    @Override
    public String dataType() { return "NEWS"; }

    @Override
    public List<CollectionDto.CollectedItem> collect(String cursorValue) {
        CollectionDto.FastApiRealtimeFetchResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/internal/realtime-pipeline/fetch-and-triage")
                    .body(new CollectionDto.RealtimeFetchRequest(cursorValue))
                    .retrieve()
                    .body(CollectionDto.FastApiRealtimeFetchResponse.class);
        } catch (RuntimeException exception) {
            log.warn("GDELT 실시간 트리아지 호출 실패: {}", exception.getMessage());
            return List.of();
        }
        if (response == null || !response.success() || response.data() == null) {
            log.warn("GDELT 실시간 트리아지 응답이 올바르지 않습니다.");
            return List.of();
        }

        CollectionDto.RealtimeFetchResult result = response.data();
        String newCursorValue = result.newCursorValue();
        List<CollectionDto.RealtimeCandidate> candidates = result.items();

        List<CollectionDto.CollectedItem> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            CollectionDto.RealtimeCandidate candidate = candidates.get(i);
            String countryCode = gdeltEventArchiveService.fipsToIso2(candidate.actionGeoCountryCode());
            // runSource()는 마지막 항목의 newCursorValue만 커서 갱신에 사용하므로 마지막 항목에만 채운다.
            String itemCursorValue = (i == candidates.size() - 1) ? newCursorValue : null;
            items.add(new CollectionDto.CollectedItem(
                    "GDELT-" + candidate.globalEventId(), candidate.title(), candidate.content(),
                    candidate.sourceUrl(), countryCode, null, itemCursorValue, candidate.goldsteinScale()));
        }
        if (items.isEmpty() && newCursorValue != null) {
            // 트리아지 통과 후보가 0건이어도 커서는 전진해야 다음 주기에 같은 구간을 반복 조회하지
            // 않는다. runSource()는 dedup에 걸려도(continue) 그 항목의 newCursorValue는 그대로
            // 커서 갱신에 쓰므로, external_id를 고정값으로 둬서 최초 1회만 실제 저장·분석 트리거가
            // 일어나고 그 뒤로는 항상 dedup으로 스킵되면서 커서만 전진하게 만든다.
            items.add(new CollectionDto.CollectedItem(
                    "GDELT-CURSOR-ADVANCE-SENTINEL", null, null, null, null, null, newCursorValue, null));
        }
        log.info("GDELT 실시간 트리아지 완료: {}건 크롤링 성공, 새 커서={}", candidates.size(), newCursorValue);
        return items;
    }
}

@Component
class GdeltDemoReplayAdapter implements DataSourceAdapter {
    // index별 상대 오프셋. CollectionService가 이 값을 "최신 실뉴스 상단 앵커 + 오프셋(초)"로
    // 적용해 데모를 전부 실뉴스 위 조밀한 구간에 배치한다(과거명은 _MINUTES지만 실제 단위는 초).
    // 값 1,5,9,…,397 → 데모 100건이 앵커 위 약 6.6분 안에 index 순으로 깔린다.
    private static final int REPLAY_INITIAL_OFFSET_MINUTES = 1;
    private static final int REPLAY_SPACING_MINUTES = 4;

    private final RestClient fastApiRestClient;
    private final GdeltEventArchiveService gdeltEventArchiveService;
    private final int replayLimit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    GdeltDemoReplayAdapter(RestClient fastApiRestClient, GdeltEventArchiveService gdeltEventArchiveService,
            @Value("${app.demo-gdelt.replay-limit:200}") int replayLimit) {
        this.fastApiRestClient = fastApiRestClient;
        this.gdeltEventArchiveService = gdeltEventArchiveService;
        this.replayLimit = replayLimit;
    }

    public String sourceName() { return "DEMO_GDELT"; }
    public String dataType() { return "NEWS"; }

    public List<CollectionDto.CollectedItem> collect(String ignored) {
        CollectionDto.FastApiRealtimeFetchResponse response = fastApiRestClient.post()
                .uri("/api/v1/internal/realtime-pipeline/demo-replay")
                .body(new CollectionDto.DemoReplayRequest(replayLimit))
                .retrieve().body(CollectionDto.FastApiRealtimeFetchResponse.class);
        if (response == null || !response.success() || response.data() == null) return List.of();
        List<CollectionDto.CollectedItem> items = new ArrayList<>();
        int index = 0;
        for (CollectionDto.RealtimeCandidate candidate : response.data().items()) {
            String payload;
            try {
                // Map.of는 10쌍 상한이라 (C) 필드까지 담으려면 ofEntries를 쓴다. null 금지이므로 기본값으로 방어.
                payload = objectMapper.writeValueAsString(Map.ofEntries(
                        Map.entry("replayed", true),
                        Map.entry("global_event_id", candidate.globalEventId()),
                        Map.entry("original_event_date",
                                candidate.originalEventDate() == null ? "" : candidate.originalEventDate()),
                        Map.entry("num_articles", candidate.numArticles() == null ? 0 : candidate.numArticles()),
                        Map.entry("avg_tone", candidate.avgTone() == null ? 0.0 : candidate.avgTone()),
                        // 시간압축: 이 이벤트를 넣을 사이트 날짜 버킷(0=오늘,1=어제,2=그제).
                        Map.entry("demo_day", candidate.demoDay() == null ? 0 : candidate.demoDay()),
                        // (C) 추출 오버라이드용 — Spring이 LLM 재추출 대신 이 값으로 자재·관련성을 강제한다.
                        Map.entry("material_enum", candidate.materialEnum() == null ? "" : candidate.materialEnum()),
                        Map.entry("event_type", candidate.eventType() == null ? "" : candidate.eventType()),
                        Map.entry("tone_score", candidate.toneScore() == null ? 0.0 : candidate.toneScore()),
                        Map.entry("impact_domain",
                                candidate.impactDomain() == null ? "PRODUCTION" : candidate.impactDomain()),
                        // 사전 번역된 한국어 제목 — 주입 시 raw_events.title_ko에 바로 채운다(아래 CollectionService).
                        Map.entry("title_kr", candidate.titleKr() == null ? "" : candidate.titleKr()),
                        Map.entry("replay_offset_minutes",
                                REPLAY_INITIAL_OFFSET_MINUTES + index * REPLAY_SPACING_MINUTES)));
            } catch (Exception exception) {
                throw new IllegalStateException("GDELT 데모 payload 생성 실패", exception);
            }
            items.add(new CollectionDto.CollectedItem(
                    "GDELT-DEMO-" + candidate.globalEventId(), candidate.title(), candidate.content(),
                    candidate.sourceUrl(), gdeltEventArchiveService.fipsToIso2(candidate.actionGeoCountryCode()),
                    payload,
                    null, candidate.goldsteinScale()));
            index++;
        }
        return items;
    }
}

/** GDACS에서 진행 중인 재난·재해 이벤트 목록을 수집합니다. */
@Component
class GdacsDisasterAdapter extends AbstractHttpAdapter implements DataSourceAdapter {
    GdacsDisasterAdapter() {
        super("https://www.gdacs.org");
    }

    @Override
    public String sourceName() { return "GDACS"; }

    @Override
    public String dataType() { return "DISASTER"; }

    @Override
    public List<CollectionDto.CollectedItem> collect(String cursorValue) {
        List<CollectionDto.CollectedItem> items = new ArrayList<>();
        try {
            String body = restClient.get().uri("/gdacsapi/api/events/geteventlist/SEARCH")
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode feature : root.path("features")) {
                JsonNode properties = feature.path("properties");
                String externalId = properties.path("eventid").asText(null);
                if (externalId == null) continue;
                String name = properties.path("name").asText("");
                String alertLevel = properties.path("alertlevel").asText("Green");
                String iso3 = properties.path("iso3").asText(null);
                items.add(new CollectionDto.CollectedItem(
                        "GDACS-" + externalId, name, "alertlevel=" + alertLevel, properties.path("url").asText(null),
                        iso3ToIso2(iso3), properties.toString(), null, null));
            }
        } catch (Exception exception) {
            log.warn("GDACS 수집 실패 (다음 주기에 재시도): {}", exception.getMessage());
        }
        return items;
    }

    private String iso3ToIso2(String iso3) {
        if (iso3 == null) return null;
        return switch (iso3) {
            case "CHL" -> "CL";
            case "IDN" -> "ID";
            case "AUS" -> "AU";
            case "COD" -> "CD";
            case "ARG" -> "AR";
            default -> null;
        };
    }
}

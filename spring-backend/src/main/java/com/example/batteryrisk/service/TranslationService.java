package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.TranslationDto;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 영문 뉴스 제목을 한국어로 번역해 {@code raw_events.title_ko}에 채웁니다.
 *
 * <p><b>수집과 분리된 별도 스케줄러입니다.</b> 수집 경로에 인라인으로 붙이면 번역이 실패한 기사는
 * 영영 번역될 기회가 없습니다 — 같은 기사가 다시 수집되지 않기 때문입니다(external_id dedup).
 * 분리해 두면 "title_ko가 비어 있는 행"을 주기마다 다시 집어가므로 실패해도 다음 주기에 회복되고,
 * 수집이 멈춰 있어도 이미 쌓인 기사를 번역할 수 있습니다.
 *
 * <p>제목만 번역합니다. 본문까지 번역하면 기사당 토큰이 수십 배가 되는데 마퀴·목록에 필요한 건
 * 제목뿐이고, 본문 요약이 필요하면 추출 경로에 이미 summary_kr이 있습니다.
 *
 * <p>번역이 안 된 기사도 화면에는 원문 제목으로 정상 노출됩니다 — 번역은 화면을 막지 않습니다.
 */
@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** 한 주기에 번역할 최대 건수. FastAPI의 MAX_TITLES_PER_CALL과 같은 값으로 둔다. */
    private static final int BATCH_SIZE = 30;

    /**
     * 같은 행을 몇 번까지 다시 시도할지. 제목이 깨졌거나 LLM이 매번 빠뜨리는 행이 있으면
     * 매 주기 같은 행만 집어와 뒤 기사에 순서가 오지 않는다.
     */
    private static final short MAX_ATTEMPTS = 3;

    private final RawEventRepository rawEventRepository;
    private final RestClient fastApiRestClient;

    /**
     * 번역 on/off. <b>기본 false</b> — 수집·크롤링과 달리 이쪽은 LLM 호출이라 비용이 나간다.
     * 켜려면 COLLECTION_TRANSLATION_ENABLED=true (app.collection.translation-enabled=true).
     *
     * <p>이 기본값은 application.yml·docker-compose.yml과 같아야 한다. 세 곳이 갈라지면
     * 설정 파일에서 껐다고 믿는 상태로 여기 기본값이 살아나 가드가 조용히 무력화된다
     * (analysis-enabled와 같은 방침).
     */
    @Value("${app.collection.translation-enabled:false}")
    private boolean translationEnabled;

    public TranslationService(RawEventRepository rawEventRepository, RestClient fastApiRestClient) {
        this.rawEventRepository = rawEventRepository;
        this.fastApiRestClient = fastApiRestClient;
    }

    /**
     * 5분 주기. 수집(15분)보다 촘촘한 이유는 새로 들어온 기사가 빨리 한국어가 되는 게 체감상
     * 중요해서다. 번역할 게 없으면 FastAPI를 호출하지 않으므로 빈 주기는 비용이 0이다.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 120_000)
    public void translatePendingScheduled() {
        if (!translationEnabled) {
            log.debug("제목 번역 비활성(app.collection.translation-enabled=false) — 건너뜀");
            return;
        }
        translatePending();
    }

    /**
     * 미번역 제목을 한 배치 번역해 저장합니다. 수동 트리거(POST /collection/translate)는
     * 플래그와 무관하게 이 메서드를 직접 호출합니다 — 호출 자체가 "지금 번역해보라"는 의사표시입니다.
     */
    @Transactional
    public TranslationDto.TranslationRunResult translatePending() {
        List<RawEvent> pending = rawEventRepository.findUntranslatedNews(
                MAX_ATTEMPTS, PageRequest.of(0, BATCH_SIZE));
        if (pending.isEmpty()) {
            return new TranslationDto.TranslationRunResult(0, 0, "SUCCESS", null);
        }

        TranslationDto.TranslateTitlesResult result;
        try {
            TranslationDto.FastApiTranslateResponse response = fastApiRestClient.post()
                    .uri("/api/v1/internal/translation/titles")
                    .body(new TranslationDto.TranslateTitlesRequest(pending.stream()
                            .map(event -> new TranslationDto.TitleToTranslate(
                                    String.valueOf(event.getId()), event.getTitle()))
                            .toList()))
                    .retrieve()
                    .body(TranslationDto.FastApiTranslateResponse.class);
            if (response == null || !response.success() || response.data() == null) {
                log.warn("제목 번역 응답이 올바르지 않습니다.");
                return new TranslationDto.TranslationRunResult(
                        pending.size(), 0, "FAILED", "INVALID_RESPONSE");
            }
            result = response.data();
        } catch (RuntimeException exception) {
            // 시도 횟수를 올리지 않는다 — FastAPI가 잠깐 떠 있지 않은 것과 그 제목이 번역 불가인 것은
            // 다르다. 통신 실패로 재시도 예산을 태우면 멀쩡한 기사가 영구 미번역으로 남는다.
            log.warn("제목 번역 호출 실패: {}", exception.getMessage());
            return new TranslationDto.TranslationRunResult(
                    pending.size(), 0, "FAILED", exception.getMessage());
        }

        Map<String, String> translatedById = result.items().stream()
                .collect(Collectors.toMap(
                        TranslationDto.TranslatedTitle::id,
                        TranslationDto.TranslatedTitle::titleKo,
                        (first, second) -> first));

        int translated = 0;
        for (RawEvent event : pending) {
            String titleKo = translatedById.get(String.valueOf(event.getId()));
            if (titleKo != null) {
                event.applyTranslatedTitle(titleKo);
                translated++;
            } else {
                // LLM이 빠뜨린 건. 다음 주기에 다시 시도하되 무한 반복은 막는다.
                event.markTranslationFailed();
            }
        }
        rawEventRepository.saveAll(pending);

        log.info("제목 번역: {}건 중 {}건 저장 (model={}, mock={})",
                pending.size(), translated, result.modelVersion(), result.mock());
        return new TranslationDto.TranslationRunResult(pending.size(), translated, "SUCCESS", null);
    }

}

package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.AiBriefingDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 데모 GDELT 재주입을 "뉴스 먼저, 분석·KPI는 뒤따라" 방식으로 구동한다.
 *
 * <p>1) {@code runSource("DEMO_GDELT")}로 원본만 주입 — 즉시 커밋돼 뉴스 목록·최신 뉴스 상단에
 * 바로 뜬다. 2) 이어서 KPI 반영이 안 된 데모 이벤트마다 <b>분석 보장 → '브리핑 생성' 엔드포인트
 * 자동 호출</b>(사람이 버튼 누르는 것과 동일한 {@link AiBriefingService#generate})을 이벤트별
 * 개별 트랜잭션으로 태운다. 이 경로는 커밋된 analysis를 참조하므로 procurement_risk_assessments에
 * analysis_id가 채워져 <b>KPI(구매팀·비로그인 대시보드 등)에 집계</b>된다. 자동 수집 경로는
 * flush 타이밍상 analysis_id=null로 저장돼 KPI에서 배제되므로 데모는 이 경로를 쓴다.
 *
 * <p>KPI가 위에서부터(최신 데모부터) 20~50분에 걸쳐 점진적으로 채워진다. 매니페스트가 아직
 * 준비 전이거나 일부 브리핑이 실패하면 60초 주기로 남은 것만 재시도한다(상한 초과 시 중단).
 */
@Component
public class DemoGdeltReplayStartup {
    private static final Logger log = LoggerFactory.getLogger(DemoGdeltReplayStartup.class);
    private final CollectionService collectionService;
    private final AiBriefingService aiBriefingService;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    /** 시작 이벤트와 60초 스케줄러가 동시에 재주입/분석을 태우지 않도록 막는다. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.demo-gdelt.replay-enabled:false}")
    private boolean enabled;

    @Value("${app.demo-gdelt.replay-limit:100}")
    private int expectedCount;

    public DemoGdeltReplayStartup(CollectionService collectionService, AiBriefingService aiBriefingService) {
        this.collectionService = collectionService;
        this.aiBriefingService = aiBriefingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void replayOnceOnStartup() {
        attemptReplay();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retryUntilComplete() {
        attemptReplay();
    }

    private void attemptReplay() {
        if (!enabled || completed.get()) return;
        if (!running.compareAndSet(false, true)) return;   // 동시 실행 방지
        try {
            // 1) 주입만 — 즉시 커밋돼 뉴스 목록·최신 뉴스 상단에 바로 뜬다(분석 없음).
            var result = collectionService.runSource("DEMO_GDELT");
            // 준비 판정은 "수집 0건 아님"으로 한다. 매니페스트는 이미지에 구워져 통째로(atomic)
            // 로드되므로 부분 로드가 없고, 실제 건수(예: 99)는 replay-limit(100)보다 작을 수 있어
            // 정확한 상한 비교는 영영 "준비 전"에 걸릴 수 있다. fastapi가 매니페스트를 아직 굽는
            // 중이면 /demo-replay가 빈 목록을 주므로 collected=0으로 걸러 60초 후 재시도한다.
            if (result.collected() <= 0) {
                log.warn("GDELT 데모 manifest 준비 전(수집 0건, 상한 {}); 60초 후 재시도합니다.", expectedCount);
                return;
            }
            // 2) KPI 미반영 이벤트마다: 분석 보장 → '브리핑 생성' 엔드포인트 자동 호출.
            //    각 단계가 건별로 커밋돼, 뉴스는 이미 떴고 KPI가 위에서부터 점진적으로 채워진다.
            List<Long> pending = collectionService.findPendingDemoEventIds();
            int briefed = 0;
            for (Long id : pending) {
                UUID analysisId = collectionService.ensureDemoAnalysis(id);
                if (analysisId == null) continue;   // 분석 불가(키워드 없음) — 시도 소비, 상한서 멈춤
                try {
                    // 사람이 'AI 브리핑 생성' 버튼 누르는 것과 동일한 경로(analysis_id 채움 → KPI 집계).
                    aiBriefingService.generate(new AiBriefingDto.GenerateRequest(
                            "NEWS", analysisId.toString(), true, analysisId));
                    briefed++;
                } catch (RuntimeException ex) {
                    // 개별 실패(ERP 타겟 없음 등)는 로그만 — 루프는 계속, 시도 상한에서 정리된다.
                    log.warn("데모 브리핑 생성 실패(id={}, analysisId={}): {}", id, analysisId, ex.getMessage());
                }
            }
            if (!collectionService.findPendingDemoEventIds().isEmpty()) {
                log.info("GDELT 데모 KPI 반영 진행 중(이번 회차 {}건 브리핑) — 남은 건은 60초 후 재시도합니다.", briefed);
                return;
            }
            completed.set(true);
            log.info("GDELT 데모 자동 주입·분석·브리핑 완료: collected={}, new={}, briefed(이번 회차)={}",
                    result.collected(), result.newItems(), briefed);
        } catch (RuntimeException exception) {
            log.warn("GDELT 데모 자동 주입/분석 실패; 60초 후 남은 건만 재시도합니다: {}",
                    exception.getMessage(), exception);
        } finally {
            running.set(false);
        }
    }
}

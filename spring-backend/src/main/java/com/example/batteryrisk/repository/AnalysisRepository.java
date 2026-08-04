package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.Analysis;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {
    /** F10: 일일 브리핑 대상 WARNING 분석 목록입니다. */
    List<Analysis> findBySeverityAndCompletedAtGreaterThanEqual(String severity, Instant since);

    /**
     * 공개 리스크 관제 지도(Seq 23)에 올릴 <b>완결 NEWS 사건</b>. 사건당 1건이다.
     *
     * <p>기준과 중복 제거 키는 {@link NewsEventSql}에 한 벌로 정의돼 있고 주요 알림·리스크 모니터링
     * 목록도 같은 것을 쓴다. 예전에는 지도만 {@code (국가, 자재)}로 접고 목록은 제목으로 접어서,
     * 같은 시점에 지도는 주의 3건 · 알림은 2건을 보여줬다(실측 2026-08-03).
     *
     * <p><b>등급은 그 뉴스의 완결 브리핑에서만 온다.</b> 예전에는 외부신호 등급(severity)이나
     * {@code procurement_risk_assessments}의 아무 행이나 통과시켰는데, 후자에는 계약·자재 화면이
     * 같은 뉴스를 외부신호로 끌어다 쓰면서 남긴 평가가 섞여 있다. 그 등급으로 마커를 칠하면
     * 같은 기사가 화면마다 다른 색으로 찍힌다.
     *
     * <p>등급 필터를 명시하는 이유는 그대로다 — 정상 사건까지 올라오면 마커 상한
     * ({@code RISK_BOARD_MAX_MARKERS})을 먼저 채워 심각 사건을 밀어낸다.
     *
     * <p>{@code material_category IS NOT NULL}은 목록과 같은 조건이다. GDELT 트리아지가 생산국
     * 기준으로 통과시킨 공급망 무관 기사가 마커로 올라오는 것을 막는다.
     *
     * <p>{@code ai_briefings}·{@code raw_events}는 JPA 엔티티가 아니라 JdbcTemplate으로만 다루므로
     * JPQL에서 조인할 수 없다. 그래서 네이티브 쿼리다.
     */
    @Query(nativeQuery = true, value = "WITH "
            + NewsEventSql.COMPLETED_NEWS_CTE + ",\n"
            + NewsEventSql.DEDUPED_NEWS_EVENTS_CTE + "\n"
            + """
            SELECT a.* FROM analyses a
            JOIN news_events e ON e.analysis_id = a.analysis_id
            WHERE a.country_code IS NOT NULL
              AND e.material_category IS NOT NULL
              AND e.procurement_risk_level IN ('CRITICAL', 'WARNING')
            ORDER BY e.collected_at DESC
            """)
    List<Analysis> findRiskBoardCandidates(Pageable pageable);

    /**
     * 자재 대분류별 최신 분석. "원자재 위험" 화면의 AI 브리핑이 <b>외부신호</b>로 쓸 뉴스를 고른다.
     *
     * <p>그 화면에는 뉴스가 없어서 멀티에이전트가 요구하는 외부신호(가중치 0.35)를 만들 수 없다.
     * 그래서 이미 저장돼 있는 같은 대분류의 최신 분석을 끌어다 쓴다 — 새로 수집하거나 분석하지 않는다.
     *
     * <p>{@code severityScore}·{@code countryCode}까지 요구하는 이유: 앞의 것이 없으면
     * {@link com.example.batteryrisk.service.MultiAgentOrchestrationService}가 ANALYSIS_NOT_SCORED로
     * 거부하고, 뒤의 것이 없으면 FastAPI의 KG 게이트가 매칭 없음으로 조기 종료해 ERP·계약 노드를
     * 아예 타지 않는다. 둘 다 "돌려도 의미 없는" 후보라 여기서 미리 뺀다.
     *
     * <p>NOT_RELEVANT 판정은 {@code reason_codes}가 CSV 문자열이라 SQL로 거르지 않고
     * 호출부가 여러 건을 받아 Java에서 걸러낸다.
     */
    @Query("""
            SELECT a FROM Analysis a
            WHERE a.status = 'COMPLETED'
              AND a.materialCategory = :materialCategory
              AND a.severity IS NOT NULL
              AND a.severityScore IS NOT NULL
              AND a.countryCode IS NOT NULL
            ORDER BY a.createdAt DESC
            """)
    List<Analysis> findScoredByMaterialCategory(String materialCategory, Pageable pageable);
}

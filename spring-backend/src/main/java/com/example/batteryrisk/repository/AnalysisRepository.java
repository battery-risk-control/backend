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
     * 공개 리스크 관제 지도(Seq 23)에 올릴 후보 분석입니다.
     *
     * <p>지도 마커를 그리려면 등급(severity)·국가(countryCode)·자재(materialCategory)가 모두 있어야 하므로
     * 하나라도 비면 제외한다. 최신순 정렬은 RiskEventService가 (국가, 자재) 조합별로 가장 최근 1건만
     * 남기는 중복 제거에 쓰인다 — 같은 뉴스를 반복 분석하면 마커가 같은 좌표에 겹쳐 찍히기 때문이다.
     *
     * <p>{@code severity IN (CRITICAL, WARNING)}을 명시한다 — {@code materialCategory}가
     * {@link com.example.batteryrisk.domain.Analysis#attachMaterialCategory}로 등급과 무관하게
     * 항상 채워지게 되면서, {@code materialCategory IS NOT NULL}이 우연히 겸하고 있던 등급 필터
     * 역할이 사라졌다. 이걸 명시하지 않으면 NORMAL 이벤트까지 지도에 올라와 마커 상한
     * ({@code RISK_BOARD_MAX_MARKERS})을 정상 이벤트가 먼저 채워 심각 이벤트를 밀어낼 수 있다.
     */
    @Query("""
            SELECT a FROM Analysis a
            WHERE a.status = 'COMPLETED'
              AND a.severity IN ('CRITICAL', 'WARNING')
              AND a.countryCode IS NOT NULL
              AND a.materialCategory IS NOT NULL
            ORDER BY a.createdAt DESC
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

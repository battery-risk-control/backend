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
}

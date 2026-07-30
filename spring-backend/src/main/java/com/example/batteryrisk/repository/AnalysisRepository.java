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
     */
    @Query("""
            SELECT a FROM Analysis a
            WHERE a.status = 'COMPLETED'
              AND a.severity IS NOT NULL
              AND a.countryCode IS NOT NULL
              AND a.materialCategory IS NOT NULL
            ORDER BY a.createdAt DESC
            """)
    List<Analysis> findRiskBoardCandidates(Pageable pageable);
}

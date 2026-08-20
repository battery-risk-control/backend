package com.example.batteryrisk.repository.executive;

import com.example.batteryrisk.dto.executive.ExecutiveDashboardDto;
import com.example.batteryrisk.repository.NewsEventSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 3계층 경영진 화면에만 필요한 위험 추세·검증 현황 집계 Repository. */
@Repository
public class ExecutiveDashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ExecutiveDashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 최근 30일 멀티에이전트 구매 위험 평가를 한국 날짜 기준으로 집계한다.
     *
     * <p>날짜는 분석 시각(assessed_at)이 아니라 <b>뉴스 표시 날짜(raw_events.collected_at)</b>로
     * 접는다(2026-08-19). 데모 시간압축은 collected_at을 오늘/어제/그제로 되돌리는데, assessed_at은
     * 전부 "지금"이라 추세가 오늘 막대 하나에 몰렸다 — 표시 날짜로 접어야 3일로 퍼진다. 뉴스에
     * 연결되지 않은 평가(계약·ERP·수동)는 collected_at이 없으므로 COALESCE로 assessed_at을 쓴다
     * (그쪽은 종전과 동일). 실시간 뉴스는 collected_at≈assessed_at이라 영향 없다.
     */
    public List<ExecutiveDashboardDto.RiskTrendPoint> loadRiskTrend() {
        return jdbc.query("""
                SELECT
                    (COALESCE(r.collected_at, p.assessed_at) AT TIME ZONE 'Asia/Seoul')::date AS assessed_date,
                    AVG(p.procurement_risk_score) AS average_risk_score,
                    COUNT(*) FILTER (WHERE p.procurement_risk_level = 'CRITICAL') AS critical_count,
                    COUNT(*) FILTER (WHERE p.procurement_risk_level = 'WARNING') AS warning_count
                FROM procurement_risk_assessments p
                LEFT JOIN raw_events r ON r.triggered_analysis_id = p.analysis_id
                WHERE COALESCE(r.collected_at, p.assessed_at) >= now() - INTERVAL '30 days'
                GROUP BY assessed_date
                ORDER BY assessed_date
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new ExecutiveDashboardDto.RiskTrendPoint(
                        rs.getDate("assessed_date").toLocalDate(),
                        rs.getBigDecimal("average_risk_score"),
                        rs.getLong("critical_count"),
                        rs.getLong("warning_count")));
    }

    /**
     * 최근 24시간 구매 리스크 평가의 심각/주의 <b>사건</b> 수와 최고 점수를 집계한다.
     * 경영진 KPI 카드 하단 "24h" 줄에 쓴다. 평가가 0건이면 max_risk_score는 null → 화면 "—".
     *
     * <p>1계층 {@code DashboardRepository.recent_24h}와 <b>같은 규칙</b>으로 센다(2026-08-19 정합):
     * (a) {@code DISTINCT ON (event_key)}로 같은 사건 반복 평가를 접고, (b) 완결 NEWS 브리핑
     * (composite+review_passed, {@link NewsEventSql#COMPLETED_NEWS_CTE}) 게이트를 통과한 것만,
     * (c) material_category가 있는 것만. 이전에는 raw COUNT라 같은 사건 중복·계약/ERP·미검증·자재
     * 미상 평가까지 세어 1계층(36)보다 크게 부풀었다(실측 92). 검증 브리핑 두 줄은 원래대로 유지.
     */
    public ExecutiveDashboardDto.Recent24hSummary loadRecent24hSummary() {
        return jdbc.queryForObject("WITH " + NewsEventSql.COMPLETED_NEWS_CTE + """
                ,
                recent_24h AS (
                    SELECT DISTINCT ON (cn.event_key)
                        p.procurement_risk_level, p.procurement_risk_score
                    FROM procurement_risk_assessments p
                    JOIN completed_news cn ON cn.analysis_id = p.analysis_id
                    WHERE p.material_category IS NOT NULL
                      -- 24h는 뉴스 표시 날짜(collected_at) 기준(created_at 아님) — 1계층과 동일.
                      -- 데모 시간압축이 collected_at을 오늘/어제/그제로 되돌리므로 "24h=오늘치"가 된다.
                      AND cn.collected_at >= NOW() - INTERVAL '24 hours'
                    ORDER BY cn.event_key, p.created_at DESC
                )
                SELECT
                    (SELECT COUNT(*) FROM recent_24h WHERE procurement_risk_level = 'CRITICAL') AS critical_count,
                    (SELECT COUNT(*) FROM recent_24h WHERE procurement_risk_level = 'WARNING') AS warning_count,
                    (SELECT COUNT(*) FROM ai_briefings b
                     JOIN raw_events r ON r.triggered_analysis_id = b.analysis_id
                     WHERE r.collected_at >= now() - INTERVAL '24 hours'
                       AND b.composite = TRUE AND b.briefing_text IS NOT NULL
                       AND b.review_passed IS TRUE) AS verified_briefing_count,
                    (SELECT COUNT(*) FROM ai_briefings b
                     JOIN raw_events r ON r.triggered_analysis_id = b.analysis_id
                     WHERE r.collected_at >= now() - INTERVAL '24 hours'
                       AND b.composite = TRUE AND b.briefing_text IS NOT NULL
                       AND b.review_passed IS NOT TRUE) AS review_required_count,
                    (SELECT MAX(procurement_risk_score) FROM recent_24h) AS max_risk_score
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new ExecutiveDashboardDto.Recent24hSummary(
                        rs.getLong("critical_count"),
                        rs.getLong("warning_count"),
                        rs.getLong("verified_briefing_count"),
                        rs.getLong("review_required_count"),
                        rs.getBigDecimal("max_risk_score")));
    }

    /** 심각/주의 뉴스 <b>사건 건수</b>(2·3계층 메인 카드용). */
    public record RiskEventCounts(long criticalCount, long warningCount) {}

    /**
     * 2·3계층 메인 "심각 N건 / 주의 M건"의 N·M — <b>완결 브리핑까지 간 뉴스 사건 수</b>다.
     * 1계층이 자재 대분류로 접어 "원자재 N종"을 세는 것과 달리, 여기선 사건(event_key)으로 접어
     * "뉴스 건수"를 센다(2026-08-19, 라벨 "건"에 맞춤). 날짜 필터는 없다 — 데모가 원본 과거
     * 날짜(collected_at)라 뉴스피드엔 안 떠도 이 지표엔 잡히게 한다. 사건 안에서는 최신 평가를 쓴다.
     *
     * <p><b>확인 완료(acknowledge)된 평가는 제외한다</b>(2026-08-20). 1계층에서 "확인 완료"를 누르면
     * {@code procurement_risk_acknowledgements}에 assessment_id가 적히는데, 이 메인 KPI는 그걸
     * 무시해 확인 완료 항목으로 옮겨도 건수가 줄지 않았다. {@link com.example.batteryrisk.repository.DashboardRepository}
     * 의 1계층 CTE와 같은 {@code NOT EXISTS} 가드를 걸어, 확인 완료된 평가는 사건 대표 선정에서
     * 빠진다 — 사건의 모든 평가가 확인 완료면 그 사건은 통째로 카운트에서 사라진다.
     */
    public RiskEventCounts loadRiskEventCounts() {
        return jdbc.queryForObject("WITH " + NewsEventSql.COMPLETED_NEWS_CTE + """
                ,
                event_latest AS (
                    SELECT DISTINCT ON (cn.event_key) p.procurement_risk_level
                    FROM procurement_risk_assessments p
                    JOIN completed_news cn ON cn.analysis_id = p.analysis_id
                    WHERE p.material_category IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM procurement_risk_acknowledgements a
                          WHERE a.assessment_id = p.assessment_id
                      )
                    ORDER BY cn.event_key, p.created_at DESC
                )
                SELECT
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'CRITICAL') AS critical_count,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'WARNING') AS warning_count
                FROM event_latest
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new RiskEventCounts(rs.getLong("critical_count"), rs.getLong("warning_count")));
    }

    /**
     * AI 검증 화면에서 실제로 상세 조회할 수 있는 저장 브리핑과 같은 모집단을 집계한다.
     * 조기 종료(composite=false) 또는 본문이 없는 실행은 완성된 검증 대상이 아니므로 제외한다.
     */
    public ExecutiveDashboardDto.VerificationSummary loadVerificationSummary() {
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) AS total_count,
                    COUNT(*) FILTER (WHERE b.review_passed IS TRUE) AS passed_count,
                    COUNT(*) FILTER (WHERE b.review_passed IS NOT TRUE) AS review_required_count,
                    COUNT(*) FILTER (
                        WHERE b.erp_evidence IS NULL
                    ) AS erp_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE b.contract_findings IS NULL
                           OR b.contract_findings = '[]'::jsonb
                    ) AS contract_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE b.llm_error IS NOT NULL
                           OR (b.warnings IS NOT NULL AND b.warnings <> '[]'::jsonb)
                    ) AS llm_warning_count
                FROM ai_briefings b
                WHERE b.composite = TRUE
                  AND b.briefing_text IS NOT NULL
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new ExecutiveDashboardDto.VerificationSummary(
                        rs.getLong("total_count"),
                        rs.getLong("passed_count"),
                        rs.getLong("review_required_count"),
                        rs.getLong("erp_evidence_missing_count"),
                        rs.getLong("contract_evidence_missing_count"),
                        rs.getLong("llm_warning_count")));
    }
}

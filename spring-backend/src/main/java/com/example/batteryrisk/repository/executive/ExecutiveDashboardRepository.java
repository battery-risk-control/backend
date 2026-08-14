package com.example.batteryrisk.repository.executive;

import com.example.batteryrisk.dto.executive.ExecutiveDashboardDto;
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

    /** 최근 30일 멀티에이전트 구매 위험 평가를 한국 날짜 기준으로 집계한다. */
    public List<ExecutiveDashboardDto.RiskTrendPoint> loadRiskTrend() {
        return jdbc.query("""
                SELECT
                    (assessed_at AT TIME ZONE 'Asia/Seoul')::date AS assessed_date,
                    AVG(procurement_risk_score) AS average_risk_score,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'CRITICAL') AS critical_count,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'WARNING') AS warning_count
                FROM procurement_risk_assessments
                WHERE assessed_at >= now() - INTERVAL '30 days'
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
     * 최근 24시간에 생성된 구매 리스크 평가의 건수와 최고 점수를 집계한다.
     * 1계층 대시보드의 24h 보조 지표와 같은 취지로, 경영진 KPI 카드 하단 "24h" 줄에 쓴다.
     * 평가가 0건이면 max_risk_score는 null이 되고 화면은 "—"로 표시한다.
     */
    public ExecutiveDashboardDto.Recent24hSummary loadRecent24hSummary() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM procurement_risk_assessments
                     WHERE created_at >= now() - INTERVAL '24 hours'
                       AND procurement_risk_level = 'CRITICAL') AS critical_count,
                    (SELECT COUNT(*) FROM procurement_risk_assessments
                     WHERE created_at >= now() - INTERVAL '24 hours'
                       AND procurement_risk_level = 'WARNING') AS warning_count,
                    (SELECT COUNT(*) FROM ai_briefings
                     WHERE created_at >= now() - INTERVAL '24 hours'
                       AND composite = TRUE AND briefing_text IS NOT NULL
                       AND review_passed IS TRUE) AS verified_briefing_count,
                    (SELECT COUNT(*) FROM ai_briefings
                     WHERE created_at >= now() - INTERVAL '24 hours'
                       AND composite = TRUE AND briefing_text IS NOT NULL
                       AND review_passed IS NOT TRUE) AS review_required_count,
                    (SELECT MAX(procurement_risk_score) FROM procurement_risk_assessments
                     WHERE created_at >= now() - INTERVAL '24 hours') AS max_risk_score
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new ExecutiveDashboardDto.Recent24hSummary(
                        rs.getLong("critical_count"),
                        rs.getLong("warning_count"),
                        rs.getLong("verified_briefing_count"),
                        rs.getLong("review_required_count"),
                        rs.getBigDecimal("max_risk_score")));
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

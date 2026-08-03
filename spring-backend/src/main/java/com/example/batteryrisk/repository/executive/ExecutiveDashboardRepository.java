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

    /** 자재 대분류별 최신 평가만 남겨 Verification Node와 근거 완전성을 집계한다. */
    public ExecutiveDashboardDto.VerificationSummary loadVerificationSummary() {
        return jdbc.queryForObject("""
                WITH latest AS (
                    SELECT DISTINCT ON (COALESCE(material_category, assessment_id::text))
                        review_passed,
                        erp_exposure_score,
                        erp_assessment,
                        contract_findings,
                        warnings
                    FROM procurement_risk_assessments
                    ORDER BY COALESCE(material_category, assessment_id::text), created_at DESC
                )
                SELECT
                    COUNT(*) AS total_count,
                    COUNT(*) FILTER (WHERE review_passed IS TRUE) AS passed_count,
                    COUNT(*) FILTER (WHERE review_passed IS NOT TRUE) AS review_required_count,
                    COUNT(*) FILTER (
                        WHERE erp_exposure_score IS NULL OR erp_assessment IS NULL
                    ) AS erp_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE contract_findings IS NULL
                           OR contract_findings = '[]'::jsonb
                    ) AS contract_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE warnings IS NOT NULL
                          AND warnings::text ILIKE '%llm%'
                    ) AS llm_warning_count
                FROM latest
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

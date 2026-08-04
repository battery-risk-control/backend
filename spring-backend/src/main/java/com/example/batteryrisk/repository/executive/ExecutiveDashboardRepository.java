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
     * 자재 대분류별 최신 평가만 남겨 Verification Node와 근거 완전성을 집계한다.
     *
     * <p>브리핑 유래 근거 2종(contract_findings·warnings)은 procurement_risk_assessments가
     * 아니라 ai_briefings에서 읽는다 — 상류(#15 기준)에는 V24가 붙인 컬럼이 있었지만 이
     * 저장소는 V29가 그 컬럼들을 DROP하고 정본을 ai_briefings로 옮겼다(2계층
     * findBriefingDetail과 같은 경위). 평가에 아직 브리핑이 없으면 b가 NULL로 남아 계약
     * 근거 누락으로 집계된다 — "근거가 저장 안 됐다"는 뜻이므로 의미가 맞다.
     */
    public ExecutiveDashboardDto.VerificationSummary loadVerificationSummary() {
        return jdbc.queryForObject("""
                WITH latest AS (
                    SELECT DISTINCT ON (COALESCE(p.material_category, p.assessment_id::text))
                        p.assessment_id,
                        p.review_passed,
                        p.erp_exposure_score,
                        p.erp_assessment
                    FROM procurement_risk_assessments p
                    ORDER BY COALESCE(p.material_category, p.assessment_id::text), p.created_at DESC
                )
                SELECT
                    COUNT(*) AS total_count,
                    COUNT(*) FILTER (WHERE latest.review_passed IS TRUE) AS passed_count,
                    COUNT(*) FILTER (WHERE latest.review_passed IS NOT TRUE) AS review_required_count,
                    COUNT(*) FILTER (
                        WHERE latest.erp_exposure_score IS NULL OR latest.erp_assessment IS NULL
                    ) AS erp_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE b.contract_findings IS NULL
                           OR b.contract_findings = '[]'::jsonb
                    ) AS contract_evidence_missing_count,
                    COUNT(*) FILTER (
                        WHERE b.warnings IS NOT NULL
                          AND b.warnings::text ILIKE '%llm%'
                    ) AS llm_warning_count
                FROM latest
                LEFT JOIN LATERAL (
                    SELECT contract_findings, warnings
                    FROM ai_briefings b
                    WHERE b.assessment_id = latest.assessment_id
                    ORDER BY b.created_at DESC
                    LIMIT 1
                ) b ON TRUE
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

package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 멀티에이전트 구매 리스크 점수를 PostgreSQL에 저장·조회한다.
 *
 * <p>{@link SeverityRepository}와 같은 방식이다 — JPA 엔티티 대신 DTO를 직접 다루고
 * JSONB 컬럼은 {@code CAST(:param AS JSONB)}로 넣는다.
 */
@Repository
public class ProcurementRiskRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProcurementRiskRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(ProcurementRiskDto.Assessment assessment) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("assessmentId", assessment.assessmentId())
                .addValue("analysisId", assessment.analysisId())
                .addValue("newsId", assessment.newsId())
                .addValue("materialId", assessment.materialId())
                .addValue("erpMaterialId", assessment.erpMaterialId())
                .addValue("erpSupplierId", assessment.erpSupplierId())
                .addValue("materialCategory", assessment.materialCategory())
                .addValue("impactDomainFinal", assessment.impactDomainFinal())
                .addValue("assessedAt", assessment.assessedAt())
                .addValue("externalSignalScore", assessment.externalSignalScore())
                .addValue("externalSignalLevel", assessment.externalSignalLevel())
                .addValue("erpExposureScore", assessment.erpExposureScore())
                .addValue("contractGapScore", assessment.contractGapScore())
                .addValue("procurementRiskScore", assessment.procurementRiskScore())
                .addValue("procurementRiskLevel", assessment.procurementRiskLevel())
                .addValue("riskReasons", toJson(assessment.riskReasons()))
                .addValue("erpAssessment", toJson(assessment.erpAssessment()))
                .addValue("contractAssessment", toJson(assessment.contractAssessment()))
                .addValue("weightVersion", assessment.weightVersion())
                .addValue("stockoutGateApplied", assessment.stockoutGateApplied())
                .addValue("reviewPassed", assessment.reviewPassed())
                .addValue("llmUsed", assessment.llmUsed())
                .addValue("mock", assessment.mock())
                .addValue("createdAt", assessment.createdAt());
        jdbc.update("""
                INSERT INTO procurement_risk_assessments (
                    assessment_id, analysis_id, news_id, material_id, erp_material_id,
                    erp_supplier_id, material_category, impact_domain_final, assessed_at,
                    external_signal_score, external_signal_level, erp_exposure_score,
                    contract_gap_score, procurement_risk_score, procurement_risk_level,
                    risk_reasons, erp_assessment, contract_assessment,
                    weight_version, stockout_gate_applied, review_passed, llm_used, mock, created_at
                ) VALUES (
                    :assessmentId, :analysisId, :newsId, :materialId, :erpMaterialId,
                    :erpSupplierId, :materialCategory, :impactDomainFinal, :assessedAt,
                    :externalSignalScore, :externalSignalLevel, :erpExposureScore,
                    :contractGapScore, :procurementRiskScore, :procurementRiskLevel,
                    CAST(:riskReasons AS JSONB), CAST(:erpAssessment AS JSONB),
                    CAST(:contractAssessment AS JSONB),
                    :weightVersion, :stockoutGateApplied, :reviewPassed, :llmUsed, :mock, :createdAt
                )
                """, params);
    }

    /**
     * 아직 구매 리스크 점수가 없는 분석 id를 최신순으로 가져온다. 스케줄러 대상 선정용이다.
     *
     * <p>{@code NOT EXISTS}가 멱등성을 만든다 — 같은 분석을 두 번 돌려 중복 행을 쌓지 않는다.
     * 이 테이블은 append-only라 유니크 제약이 없으므로 중복 방지는 여기가 유일한 방어선이다.
     *
     * <p>{@code NOT_RELEVANT} 걸러내기는 여기서 한 번 좁히고, 실제 차단은
     * {@code resolveExternalSignal}이 CSV를 split해서 정확히 판정한다 — LIKE는 대상 축소용이다.
     */
    public List<UUID> findUnscoredAnalysisIds(int limit) {
        return jdbc.query("""
                SELECT a.analysis_id
                FROM analyses a
                WHERE a.status = 'COMPLETED'
                  AND a.severity_score IS NOT NULL
                  AND a.severity IS NOT NULL
                  AND a.material_category IS NOT NULL
                  AND (a.reason_codes IS NULL OR a.reason_codes NOT LIKE '%NOT_RELEVANT%')
                  AND NOT EXISTS (
                      SELECT 1 FROM procurement_risk_assessments p
                      WHERE p.analysis_id = a.analysis_id
                  )
                ORDER BY a.created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit),
                (rs, rowNumber) -> rs.getObject("analysis_id", UUID.class));
    }

    public Optional<ProcurementRiskDto.Assessment> findById(UUID assessmentId) {
        List<ProcurementRiskDto.Assessment> values = jdbc.query("""
                SELECT *
                FROM procurement_risk_assessments
                WHERE assessment_id = :assessmentId
                """, new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, rowNumber) -> mapAssessment(rs));
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private ProcurementRiskDto.Assessment mapAssessment(ResultSet rs) throws SQLException {
        return new ProcurementRiskDto.Assessment(
                rs.getObject("assessment_id", UUID.class),
                rs.getObject("analysis_id", UUID.class),
                rs.getString("news_id"),
                nullableLong(rs, "material_id"),
                rs.getString("erp_material_id"),
                rs.getString("erp_supplier_id"),
                rs.getString("material_category"),
                rs.getString("impact_domain_final"),
                rs.getObject("assessed_at", OffsetDateTime.class),
                rs.getBigDecimal("external_signal_score"),
                rs.getString("external_signal_level"),
                rs.getBigDecimal("erp_exposure_score"),
                rs.getBigDecimal("contract_gap_score"),
                rs.getBigDecimal("procurement_risk_score"),
                rs.getString("procurement_risk_level"),
                fromJson(rs.getString("risk_reasons"), STRING_LIST),
                fromJson(rs.getString("erp_assessment"), OBJECT_MAP),
                fromJson(rs.getString("contract_assessment"), OBJECT_MAP),
                rs.getString("weight_version"),
                rs.getBoolean("stockout_gate_applied"),
                nullableBoolean(rs, "review_passed"),
                rs.getBoolean("llm_used"),
                rs.getBoolean("mock"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("구매 리스크 JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("구매 리스크 JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}

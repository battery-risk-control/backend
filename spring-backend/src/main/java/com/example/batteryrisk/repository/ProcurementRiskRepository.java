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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public Optional<ProcurementRiskDto.Assessment> findById(UUID assessmentId) {
        List<ProcurementRiskDto.Assessment> values = jdbc.query("""
                SELECT *
                FROM procurement_risk_assessments
                WHERE assessment_id = :assessmentId
                """, new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, rowNumber) -> mapAssessment(rs));
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 목록 화면용 경량 조회 — {@code analysis_id}별 <b>최신 종합</b> 등급만 뽑는다.
     *
     * <p>한 줄에 필요한 건 등급 하나뿐인데 {@link #findById}처럼 전체 Assessment(JSONB 4개 포함)를
     * 읽으면 목록 20건에 대해 불필요한 파싱이 반복된다. 그래서 두 컬럼만 가져온다.
     *
     * <p>같은 뉴스가 여러 번 평가될 수 있어(append-only 이력) {@code DISTINCT ON}으로 최신 1건만 남긴다.
     *
     * <p><b>{@code erp_exposure_score IS NOT NULL} 조건이 핵심이다.</b> LangGraph는 KG 게이트에서
     * 매칭이 없거나 재고가 충분하면 ERP·계약 노드를 건너뛰고 조기 종료하는데, 그때도 행은 남고
     * 점수 0 · 등급 NORMAL로 기록된다. 이 조건이 없으면 <b>평가하지 못한 뉴스가 "종합 판정 결과
     * 정상(확정)"으로 화면에 뜬다</b> — 실제로 kg_service가 떠 있지 않으면 모든 실행이 이 경로다.
     * 조기 종료 경로는 {@code erp_assessment}를 빈 dict로 두므로 이 컬럼이 반드시 비어 있다.
     *
     * <p>조기 종료가 더 최신이어도 과거의 종합 판정을 살린다 — 둘 중 실제로 ERP·계약을 본 것은
     * 그쪽뿐이라, 아무 판정 없음보다 낫다.
     */
    public Map<UUID, String> findLatestRiskLevelsByAnalysisIds(Collection<UUID> analysisIds) {
        if (analysisIds == null || analysisIds.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<UUID, String>> rows = jdbc.query("""
                SELECT DISTINCT ON (analysis_id) analysis_id, procurement_risk_level
                FROM procurement_risk_assessments
                WHERE analysis_id IN (:analysisIds)
                  AND erp_exposure_score IS NOT NULL
                ORDER BY analysis_id, created_at DESC
                """, new MapSqlParameterSource("analysisIds", analysisIds),
                (rs, rowNumber) -> Map.entry(
                        rs.getObject("analysis_id", UUID.class),
                        rs.getString("procurement_risk_level")));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 분석 1건의 <b>최신</b> 종합 평가 전체. 리스크 모니터링 상세가 세부 점수 3개와 판단 근거를
     * 함께 보여줘야 해서, 등급만 뽑는 {@link #findLatestRiskLevelsByAnalysisIds}와 달리 전체를 읽는다.
     *
     * <p>append-only 이력이라 같은 분석에 여러 행이 쌓인다 — 가장 최근 것이 화면이 보여줄 값이다.
     */
    public Optional<ProcurementRiskDto.Assessment> findLatestByAnalysisId(UUID analysisId) {
        List<ProcurementRiskDto.Assessment> values = jdbc.query("""
                SELECT *
                FROM procurement_risk_assessments
                WHERE analysis_id = :analysisId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource("analysisId", analysisId),
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

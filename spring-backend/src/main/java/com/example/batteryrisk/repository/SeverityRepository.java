package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.SeverityDto;
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

/** Spring이 소유하는 Severity 입력 Snapshot과 FastAPI 결과를 PostgreSQL에 저장합니다. */
@Repository
public class SeverityRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SeverityRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(SeverityDto.AssessmentResponse assessment) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("assessmentId", assessment.assessmentId())
                .addValue("materialId", assessment.materialId())
                .addValue("supplierId", assessment.supplierId())
                .addValue("erpMaterialId", assessment.erpMaterialId())
                .addValue("erpSupplierId", assessment.erpSupplierId())
                .addValue("assessedAt", assessment.assessedAt())
                .addValue("inventoryDays", assessment.inventoryDays())
                .addValue("safetyStockDays", assessment.safetyStockDays())
                .addValue("expectedSupplyGapDays", assessment.expectedSupplyGapDays())
                .addValue("supplierDependencyRatio", assessment.supplierDependencyRatio())
                .addValue("priceChangeRate", assessment.priceChangeRate())
                .addValue("logisticsDelayDays", assessment.logisticsDelayDays())
                .addValue("gdacsAlertLevel", assessment.gdacsAlertLevel())
                .addValue("feocStatus", assessment.feocStatus())
                .addValue("dataQualityStatus", assessment.dataQualityStatus())
                .addValue("severity", assessment.severity())
                .addValue("score", assessment.score())
                .addValue("reasonCodes", toJson(assessment.reasonCodes()))
                .addValue("calculationDetails", toJson(assessment.calculationDetails()))
                .addValue("ruleVersion", assessment.ruleVersion())
                .addValue("mock", assessment.mock())
                .addValue("createdAt", assessment.createdAt());
        jdbc.update("""
                INSERT INTO severity_assessments (
                    assessment_id, material_id, supplier_id, erp_material_id, erp_supplier_id,
                    assessed_at, inventory_days, safety_stock_days, expected_supply_gap_days,
                    supplier_dependency_ratio, price_change_rate, logistics_delay_days,
                    gdacs_alert_level, feoc_status, data_quality_status, severity, score,
                    reason_codes, calculation_details, rule_version, mock, created_at
                ) VALUES (
                    :assessmentId, :materialId, :supplierId, :erpMaterialId, :erpSupplierId,
                    :assessedAt, :inventoryDays, :safetyStockDays, :expectedSupplyGapDays,
                    :supplierDependencyRatio, :priceChangeRate, :logisticsDelayDays,
                    :gdacsAlertLevel, :feocStatus, :dataQualityStatus, :severity, :score,
                    CAST(:reasonCodes AS JSONB), CAST(:calculationDetails AS JSONB),
                    :ruleVersion, :mock, :createdAt
                )
                """, params);
    }

    public Optional<SeverityDto.AssessmentResponse> findById(UUID assessmentId) {
        List<SeverityDto.AssessmentResponse> values = jdbc.query("""
                SELECT *
                FROM severity_assessments
                WHERE assessment_id = :assessmentId
                """, new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, rowNumber) -> mapAssessment(rs));
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private SeverityDto.AssessmentResponse mapAssessment(ResultSet rs) throws SQLException {
        return new SeverityDto.AssessmentResponse(
                rs.getObject("assessment_id", UUID.class),
                rs.getLong("material_id"),
                rs.getLong("supplier_id"),
                rs.getString("erp_material_id"),
                rs.getString("erp_supplier_id"),
                rs.getObject("assessed_at", OffsetDateTime.class),
                rs.getBigDecimal("inventory_days"),
                rs.getBigDecimal("safety_stock_days"),
                rs.getBigDecimal("expected_supply_gap_days"),
                rs.getBigDecimal("supplier_dependency_ratio"),
                rs.getBigDecimal("price_change_rate"),
                rs.getBigDecimal("logistics_delay_days"),
                nullableInteger(rs, "gdacs_alert_level"),
                rs.getString("feoc_status"),
                rs.getString("data_quality_status"),
                rs.getString("severity"),
                rs.getBigDecimal("score"),
                fromJson(rs.getString("reason_codes"), STRING_LIST),
                fromJson(rs.getString("calculation_details"), OBJECT_MAP),
                rs.getString("rule_version"),
                rs.getBoolean("mock"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Severity JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Severity JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}

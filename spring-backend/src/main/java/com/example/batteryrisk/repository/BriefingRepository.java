package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.BriefingDto;
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
import java.util.Optional;
import java.util.UUID;

/** 13단계 브리핑 본문과 참조 근거를 PostgreSQL에 저장·조회합니다. */
@Repository
public class BriefingRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<BriefingDto.ContractEvidence>> EVIDENCE_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<BriefingDto.AlternativeSupplier>> SUPPLIER_LIST =
            new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BriefingRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(BriefingDto.BriefingResponse briefing) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("briefingId", briefing.briefingId())
                .addValue("materialId", briefing.materialId())
                .addValue("supplierId", briefing.supplierId())
                .addValue("erpMaterialId", briefing.erpMaterialId())
                .addValue("erpSupplierId", briefing.erpSupplierId())
                .addValue("assessedAt", briefing.assessedAt())
                .addValue("severity", briefing.severity())
                .addValue("score", briefing.score())
                .addValue("headline", briefing.headline())
                .addValue("riskSummary", briefing.riskSummary())
                .addValue("inventorySummary", briefing.inventorySummary())
                .addValue("supplierDependencySummary", briefing.supplierDependencySummary())
                .addValue("supplyGapSummary", briefing.supplyGapSummary())
                .addValue("contractEvidenceSummary", briefing.contractEvidenceSummary())
                .addValue("alternativeSupplierSummary", briefing.alternativeSupplierSummary())
                .addValue("reasonCodes", toJson(briefing.reasonCodes()))
                .addValue("recommendedChecks", toJson(briefing.recommendedChecks()))
                .addValue("warnings", toJson(briefing.warnings()))
                .addValue("contractEvidence", toJson(briefing.contractEvidence()))
                .addValue("alternativeSuppliers", toJson(briefing.alternativeSuppliers()))
                .addValue("templateVersion", briefing.templateVersion())
                .addValue("ruleVersion", briefing.ruleVersion())
                .addValue("mock", briefing.mock())
                .addValue("createdAt", briefing.createdAt());
        jdbc.update("""
                INSERT INTO briefings (
                    briefing_id, material_id, supplier_id, erp_material_id, erp_supplier_id,
                    assessed_at, severity, score, headline, risk_summary, inventory_summary,
                    supplier_dependency_summary, supply_gap_summary, contract_evidence_summary,
                    alternative_supplier_summary, reason_codes, recommended_checks, warnings,
                    contract_evidence, alternative_suppliers, template_version, rule_version,
                    mock, created_at
                ) VALUES (
                    :briefingId, :materialId, :supplierId, :erpMaterialId, :erpSupplierId,
                    :assessedAt, :severity, :score, :headline, :riskSummary, :inventorySummary,
                    :supplierDependencySummary, :supplyGapSummary, :contractEvidenceSummary,
                    :alternativeSupplierSummary, CAST(:reasonCodes AS JSONB),
                    CAST(:recommendedChecks AS JSONB), CAST(:warnings AS JSONB),
                    CAST(:contractEvidence AS JSONB), CAST(:alternativeSuppliers AS JSONB),
                    :templateVersion, :ruleVersion, :mock, :createdAt
                )
                """, params);
    }

    public Optional<BriefingDto.BriefingResponse> findById(UUID briefingId) {
        List<BriefingDto.BriefingResponse> values = jdbc.query("""
                SELECT *
                FROM briefings
                WHERE briefing_id = :briefingId
                """, new MapSqlParameterSource("briefingId", briefingId),
                (rs, rowNumber) -> mapBriefing(rs));
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private BriefingDto.BriefingResponse mapBriefing(ResultSet rs) throws SQLException {
        return new BriefingDto.BriefingResponse(
                rs.getObject("briefing_id", UUID.class),
                rs.getLong("material_id"),
                rs.getLong("supplier_id"),
                rs.getString("erp_material_id"),
                rs.getString("erp_supplier_id"),
                rs.getObject("assessed_at", OffsetDateTime.class),
                rs.getString("severity"),
                rs.getBigDecimal("score"),
                rs.getString("headline"),
                rs.getString("risk_summary"),
                rs.getString("inventory_summary"),
                rs.getString("supplier_dependency_summary"),
                rs.getString("supply_gap_summary"),
                rs.getString("contract_evidence_summary"),
                rs.getString("alternative_supplier_summary"),
                fromJson(rs.getString("reason_codes"), STRING_LIST),
                fromJson(rs.getString("recommended_checks"), STRING_LIST),
                fromJson(rs.getString("warnings"), STRING_LIST),
                fromJson(rs.getString("contract_evidence"), EVIDENCE_LIST),
                fromJson(rs.getString("alternative_suppliers"), SUPPLIER_LIST),
                rs.getString("template_version"),
                rs.getString("rule_version"),
                rs.getBoolean("mock"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("브리핑 JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("브리핑 JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}

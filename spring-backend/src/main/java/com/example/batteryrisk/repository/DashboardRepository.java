package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.DashboardDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 14단계 조회·집계 전용 Repository. 화면이 바로 쓸 수 있는 형태로 PostgreSQL에서 집계한다. */
@Repository
public class DashboardRepository {

    /** 자재별 최신 Severity 1건만 남기는 공통 CTE. 현재 상태 집계의 기준이다. */
    private static final String LATEST_ASSESSMENT_CTE = """
            WITH latest AS (
                SELECT DISTINCT ON (material_id)
                    material_id, erp_material_id, severity, score, inventory_days,
                    safety_stock_days, supplier_dependency_ratio, feoc_status,
                    data_quality_status, assessed_at, created_at
                FROM severity_assessments
                ORDER BY material_id, created_at DESC
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardDto.Summary loadSummary() {
        return jdbc.queryForObject(LATEST_ASSESSMENT_CTE + """
                SELECT
                    (SELECT COUNT(*) FROM latest) AS assessed_material_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'CRITICAL') AS critical_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'WARNING') AS warning_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'NORMAL') AS normal_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'UNKNOWN') AS unknown_count,
                    (SELECT MAX(assessed_at) FROM latest) AS latest_assessed_at,
                    (SELECT COUNT(*) FROM briefings) AS briefing_count,
                    (SELECT COUNT(*) FROM materials WHERE active = TRUE) AS material_count,
                    (SELECT COUNT(*) FROM suppliers WHERE active = TRUE) AS supplier_count,
                    (SELECT COUNT(*) FROM contracts) AS contract_count,
                    (SELECT COUNT(*) FROM contract_documents) AS document_count
                """, new MapSqlParameterSource(), (rs, rowNumber) -> new DashboardDto.Summary(
                rs.getLong("assessed_material_count"),
                rs.getLong("critical_count"),
                rs.getLong("warning_count"),
                rs.getLong("normal_count"),
                rs.getLong("unknown_count"),
                rs.getLong("briefing_count"),
                rs.getLong("material_count"),
                rs.getLong("supplier_count"),
                rs.getLong("contract_count"),
                rs.getLong("document_count"),
                rs.getObject("latest_assessed_at", OffsetDateTime.class),
                true));
    }

    /** 자재별 현재 리스크. 아직 분석되지 않은 자재는 제외한다. */
    public List<DashboardDto.MaterialRiskItem> findMaterialRisks(String severity, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("severity", severity, Types.VARCHAR)
                .addValue("limit", limit);
        return jdbc.query(LATEST_ASSESSMENT_CTE + """
                SELECT l.erp_material_id, m.material_name, l.severity, l.score,
                       l.inventory_days, l.safety_stock_days, l.supplier_dependency_ratio,
                       l.feoc_status, l.data_quality_status, l.assessed_at
                FROM latest l
                JOIN materials m ON m.material_id = l.material_id
                WHERE (:severity IS NULL OR l.severity = :severity)
                ORDER BY
                    CASE l.severity
                        WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1
                        WHEN 'UNKNOWN' THEN 2 ELSE 3 END,
                    l.score DESC
                LIMIT :limit
                """, params, (rs, rowNumber) -> new DashboardDto.MaterialRiskItem(
                rs.getString("erp_material_id"),
                rs.getString("material_name"),
                rs.getString("severity"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("inventory_days"),
                rs.getBigDecimal("safety_stock_days"),
                rs.getBigDecimal("supplier_dependency_ratio"),
                rs.getString("feoc_status"),
                rs.getString("data_quality_status"),
                rs.getObject("assessed_at", OffsetDateTime.class)));
    }

    public List<DashboardDto.ImportDependencyItem> findImportDependency(
            String erpMaterialId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpMaterialId", erpMaterialId)
                .addValue("asOfDate", asOfDate);
        return jdbc.query("""
                SELECT s.erp_supplier_id, s.supplier_name, s.country_code,
                       sm.supply_share_ratio, sm.approved_status, sm.is_alternative
                FROM supplier_materials sm
                JOIN suppliers s ON s.supplier_id = sm.supplier_id
                JOIN materials m ON m.material_id = sm.material_id
                WHERE m.erp_material_id = :erpMaterialId
                  AND sm.valid_from <= :asOfDate
                  AND (sm.valid_to IS NULL OR sm.valid_to >= :asOfDate)
                ORDER BY sm.supply_share_ratio DESC, sm.priority_rank
                """, params, (rs, rowNumber) -> new DashboardDto.ImportDependencyItem(
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("country_code"),
                rs.getBigDecimal("supply_share_ratio"),
                rs.getString("approved_status"),
                rs.getBoolean("is_alternative")));
    }

    public String findMaterialName(String erpMaterialId) {
        List<String> names = jdbc.query(
                "SELECT material_name FROM materials WHERE erp_material_id = :erpMaterialId",
                new MapSqlParameterSource("erpMaterialId", erpMaterialId),
                (rs, rowNumber) -> rs.getString("material_name"));
        return names.isEmpty() ? null : names.get(0);
    }

    public List<DashboardDto.ContractItem> findContractPage(String status, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query("""
                SELECT c.contract_id, c.erp_contract_id, c.contract_number, c.contract_name,
                       s.erp_supplier_id, s.supplier_name,
                       m.erp_material_id, m.material_name,
                       c.status, c.contract_role, c.start_date, c.end_date
                FROM contracts c
                JOIN suppliers s ON s.supplier_id = c.supplier_id
                LEFT JOIN materials m ON m.material_id = c.material_id
                WHERE (:status IS NULL OR c.status = :status)
                ORDER BY c.contract_id
                LIMIT :limit OFFSET :offset
                """, params, DashboardRepository::mapContract);
    }

    public long countContracts(String status) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contracts
                WHERE (:status IS NULL OR status = :status)
                """, new MapSqlParameterSource().addValue("status", status, Types.VARCHAR), Long.class);
        return count == null ? 0L : count;
    }

    private static DashboardDto.ContractItem mapContract(ResultSet rs, int rowNumber) throws SQLException {
        return new DashboardDto.ContractItem(
                rs.getLong("contract_id"),
                rs.getString("erp_contract_id"),
                rs.getString("contract_number"),
                rs.getString("contract_name"),
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("erp_material_id"),
                rs.getString("material_name"),
                rs.getString("status"),
                rs.getString("contract_role"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class));
    }
}

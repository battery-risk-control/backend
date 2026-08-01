package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.MaterialRiskDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 1계층 "원자재 위험" 화면 전용 조회 Repository.
 *
 * <p>재고·소비·발주 같은 계산 원천값은 {@link ErpRepository}가 이미 다 갖고 있다. 여기 있는 것은
 * <b>화면에만 필요한 곁다리 조회</b> 세 개뿐이다 — 평가 대상 자재 목록, 공급사 표시명, 연결 계약
 * 카드. 이걸 ErpRepository에 밀어 넣으면 F1 계산용 Repository에 화면 관심사가 섞이므로
 * {@link DashboardRepository}와 같은 방식으로 화면별 Repository를 따로 둔다.
 */
@Repository
public class MaterialRiskRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MaterialRiskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 평가 대상 자재 목록.
     *
     * <p>{@code erp_material_id}가 없는 자재는 제외한다 — ERP Exposure Agent가 자재를 그 ID로
     * 식별하므로 없으면 애초에 요청을 만들 수 없다(마이그레이션 참조 시드로 들어온 'C1-NICKEL'이
     * 여기 해당한다). {@code active = FALSE}인 단종 자재도 뺀다.
     *
     * <p>정렬은 {@code criticality} 우선이다. 점수 순 정렬은 서비스가 평가를 마친 뒤에 하지만,
     * 평가에 실패해 점수가 없는 자재끼리는 이 순서가 그대로 남는다.
     */
    public List<MaterialRow> findAssessableMaterials() {
        return jdbc.query("""
                SELECT erp_material_id, material_name, material_category
                FROM materials
                WHERE active = TRUE AND erp_material_id IS NOT NULL
                ORDER BY
                    CASE criticality
                        WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                        WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                    material_id
                """, new MapSqlParameterSource(), (rs, rowNum) -> new MaterialRow(
                rs.getString("erp_material_id"),
                rs.getString("material_name"),
                rs.getString("material_category")));
    }

    /**
     * 계산에 실제로 쓰인 재고 스냅샷의 시각.
     *
     * <p>ERP Exposure Agent에는 이 값을 <b>보내지 않는다</b>({@code MaterialRiskService} 참고).
     * 대신 화면이 "이 숫자가 언제 기준인지"를 스스로 판단할 수 있도록 상세 응답에 그대로 실어 보낸다.
     */
    public Instant findInventorySnapshotAt(long materialId, OffsetDateTime asOf) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("asOf", asOf);
        List<Instant> snapshots = jdbc.query("""
                SELECT MAX(snapshot_at) AS snapshot_at
                FROM inventory_snapshots
                WHERE material_id = :materialId AND is_current = TRUE AND snapshot_at <= :asOf
                """, params, (rs, rowNum) -> {
            OffsetDateTime value = rs.getObject("snapshot_at", OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        });
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    /** 공급사 표시명. {@code ErpDto.ContextResponse}가 ID·상태만 주고 이름은 안 줘서 따로 읽는다. */
    public String findSupplierName(long supplierId) {
        List<String> names = jdbc.query(
                "SELECT supplier_name FROM suppliers WHERE supplier_id = :supplierId",
                new MapSqlParameterSource("supplierId", supplierId),
                (rs, rowNum) -> rs.getString("supplier_name"));
        return names.isEmpty() ? null : names.get(0);
    }

    /** 상세 패널의 "연결 계약" 카드. */
    public Optional<MaterialRiskDto.LinkedContract> findContract(long contractId) {
        List<MaterialRiskDto.LinkedContract> contracts = jdbc.query("""
                SELECT contract_id, erp_contract_id, contract_number, contract_name,
                       status, start_date, end_date
                FROM contracts
                WHERE contract_id = :contractId
                """, new MapSqlParameterSource("contractId", contractId),
                (rs, rowNum) -> new MaterialRiskDto.LinkedContract(
                        rs.getLong("contract_id"),
                        rs.getString("erp_contract_id"),
                        rs.getString("contract_number"),
                        rs.getString("contract_name"),
                        rs.getString("status"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)));
        return contracts.isEmpty() ? Optional.empty() : Optional.of(contracts.get(0));
    }

    public record MaterialRow(String erpMaterialId, String materialName, String materialCategory) {}
}

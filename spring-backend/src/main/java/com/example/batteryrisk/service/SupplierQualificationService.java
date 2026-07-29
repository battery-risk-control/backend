package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.SupplierDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * F9 공급사 자격·대체 공급사 추천의 Spring 쪽 담당: 자격 사실 조회와 필수조건 필터링입니다.
 * 필수조건(하드 필터)을 통과한 후보만 반환하며, 이 목록 밖의 후보를 FastAPI가 새로 만들어내지 않습니다.
 * 순위/위험 판단 계산은 하지 않고 사실(facts)만 제공합니다 — 순위 계산은 FastAPI가, 위험 판단은
 * ERP Exposure Agent가 담당합니다.
 *
 * 하드 필터: supplier_materials.approved_status='APPROVED'(배터리급 자재 공급 승인),
 *           suppliers.supplier_status='ACTIVE'(심사중/비활성 제외), suppliers.feoc_status='NO'(규제 부적합 제외)
 */
@Service
public class SupplierQualificationService {
    private final JdbcTemplate jdbcTemplate;

    public SupplierQualificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SupplierDto.QualifiedSuppliersResponse findQualifiedSuppliers(String materialCategory) {
        List<Long> materialIds = jdbcTemplate.queryForList(
                "SELECT material_id FROM materials WHERE material_category = ?", Long.class, materialCategory);
        if (materialIds.isEmpty()) {
            return new SupplierDto.QualifiedSuppliersResponse(materialCategory, List.of());
        }

        String inClause = materialIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] idArgs = materialIds.toArray();

        // 한 공급사가 이 카테고리의 여러 자재(예: 리튬탄산염+리튬수산화물)를 동시에 취급할 수 있어,
        // DISTINCT ON으로 공급사당 가장 우선순위 높은(priority_rank가 낮은) 자재-공급 관계 1건만 대표로 뽑습니다.
        List<SupplierDto.SupplierCandidate> candidates = jdbcTemplate.query(
                "SELECT DISTINCT ON (s.supplier_id) "
                        + "s.supplier_id, s.supplier_code, s.supplier_name, s.country_code, s.feoc_status, "
                        + "s.certifications, s.risk_level, s.supplier_status, "
                        + "sm.lead_time_days, sm.minimum_order_quantity, sm.supply_share_ratio, "
                        + "sm.priority_rank, sm.is_alternative "
                        + "FROM supplier_materials sm "
                        + "JOIN suppliers s ON s.supplier_id = sm.supplier_id "
                        + "WHERE sm.material_id IN (" + inClause + ") "
                        + "AND sm.approved_status = 'APPROVED' "
                        + "AND s.supplier_status = 'ACTIVE' "
                        + "AND s.feoc_status = 'NO' "
                        + "ORDER BY s.supplier_id, sm.priority_rank NULLS LAST",
                candidateRowMapper(), idArgs);

        return new SupplierDto.QualifiedSuppliersResponse(
                materialCategory,
                candidates.stream()
                        .map(candidate -> withLatestPrice(candidate, materialIds))
                        .toList());
    }

    private SupplierDto.SupplierCandidate withLatestPrice(
            SupplierDto.SupplierCandidate candidate, List<Long> materialIds) {
        String inClause = materialIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[materialIds.size() + 1];
        for (int i = 0; i < materialIds.size(); i++) args[i] = materialIds.get(i);
        args[materialIds.size()] = candidate.supplierId();

        List<Object[]> priceRows = jdbcTemplate.query(
                "SELECT poi.unit_price, po.order_date FROM purchase_order_items poi "
                        + "JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id "
                        + "WHERE poi.material_id IN (" + inClause + ") AND po.supplier_id = ? "
                        + "ORDER BY po.order_date DESC LIMIT 1",
                (rs, rowNum) -> new Object[]{rs.getBigDecimal("unit_price"), rs.getObject("order_date", LocalDate.class)},
                args);

        BigDecimal latestPrice = null;
        String latestOrderDate = null;
        if (!priceRows.isEmpty()) {
            latestPrice = (BigDecimal) priceRows.get(0)[0];
            LocalDate date = (LocalDate) priceRows.get(0)[1];
            latestOrderDate = date != null ? date.toString() : null;
        }

        return new SupplierDto.SupplierCandidate(
                candidate.supplierId(), candidate.supplierCode(), candidate.supplierName(), candidate.countryCode(),
                candidate.feocStatus(), candidate.certifications(),
                candidate.riskLevel(), candidate.supplierStatus(), candidate.leadTimeDays(),
                candidate.minimumOrderQuantity(), candidate.supplyShareRatio(), candidate.priorityRank(),
                candidate.isAlternative(), latestPrice, latestOrderDate);
    }

    private RowMapper<SupplierDto.SupplierCandidate> candidateRowMapper() {
        return (ResultSet rs, int rowNum) -> new SupplierDto.SupplierCandidate(
                rs.getLong("supplier_id"), rs.getString("supplier_code"), rs.getString("supplier_name"),
                rs.getString("country_code"), rs.getString("feoc_status"), rs.getString("certifications"),
                rs.getString("risk_level"), rs.getString("supplier_status"),
                (Integer) rs.getObject("lead_time_days"), rs.getBigDecimal("minimum_order_quantity"),
                rs.getBigDecimal("supply_share_ratio"), (Integer) rs.getObject("priority_rank"),
                rs.getBoolean("is_alternative"), null, null);
    }
}

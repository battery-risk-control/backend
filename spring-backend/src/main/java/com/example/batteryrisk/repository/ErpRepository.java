package com.example.batteryrisk.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** F6 외부 ERP ID를 내부 PK로 해석하고 F1 계산에 필요한 원천값만 조회합니다. */
@Repository
public class ErpRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ErpRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MaterialRow> findMaterial(String erpMaterialId) {
        return first(jdbc.query("""
                SELECT material_id, erp_material_id, material_name, base_unit
                FROM materials
                WHERE erp_material_id = :erpMaterialId AND active = TRUE
                """, new MapSqlParameterSource("erpMaterialId", erpMaterialId),
                (rs, rowNum) -> new MaterialRow(
                        rs.getLong("material_id"), rs.getString("erp_material_id"),
                        rs.getString("material_name"), rs.getString("base_unit"))));
    }

    public InventoryRow aggregateCurrentInventory(long materialId, OffsetDateTime asOf) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("asOf", asOf);
        return jdbc.queryForObject("""
                SELECT
                    COALESCE(SUM(on_hand_quantity), 0) AS on_hand_quantity,
                    COALESCE(SUM(reserved_quantity), 0) AS reserved_quantity,
                    COALESCE(SUM(blocked_quantity), 0) AS blocked_quantity,
                    COALESCE(SUM(quality_hold_quantity), 0) AS quality_hold_quantity,
                    COALESCE(SUM(safety_stock_quantity), 0) AS safety_stock_quantity,
                    COUNT(*) AS row_count,
                    SUM(CASE WHEN data_quality_flag <> 'VALID' THEN 1 ELSE 0 END) AS invalid_count
                FROM inventory_snapshots
                WHERE material_id = :materialId
                  AND is_current = TRUE
                  AND snapshot_at <= :asOf
                """, params, (rs, rowNum) -> new InventoryRow(
                rs.getBigDecimal("on_hand_quantity"),
                rs.getBigDecimal("reserved_quantity"),
                rs.getBigDecimal("blocked_quantity"),
                rs.getBigDecimal("quality_hold_quantity"),
                rs.getBigDecimal("safety_stock_quantity"),
                rs.getInt("row_count"),
                rs.getInt("invalid_count")));
    }

    public ConsumptionRow aggregateCurrentConsumption(long materialId, OffsetDateTime asOf) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("asOf", asOf);
        return jdbc.queryForObject("""
                SELECT
                    SUM(average_daily_usage) AS average_daily_usage,
                    COUNT(*) AS row_count,
                    SUM(CASE WHEN average_daily_usage IS NULL THEN 1 ELSE 0 END) AS missing_usage_count,
                    SUM(CASE WHEN data_quality_flag <> 'VALID' THEN 1 ELSE 0 END) AS invalid_count
                FROM material_consumptions
                WHERE material_id = :materialId
                  AND is_current = TRUE
                  AND calculated_at <= :asOf
                """, params, (rs, rowNum) -> new ConsumptionRow(
                rs.getBigDecimal("average_daily_usage"),
                rs.getInt("row_count"),
                rs.getInt("missing_usage_count"),
                rs.getInt("invalid_count")));
    }

    public Optional<SupplyRow> findSupply(
            long materialId, String requestedErpSupplierId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("erpSupplierId", blankToNull(requestedErpSupplierId), java.sql.Types.VARCHAR)
                .addValue("asOfDate", asOfDate);
        return first(jdbc.query("""
                SELECT
                    s.supplier_id,
                    s.erp_supplier_id,
                    s.supplier_status,
                    s.feoc_status,
                    c.contract_id,
                    c.erp_contract_id,
                    sm.supply_share_ratio
                FROM supplier_materials sm
                JOIN suppliers s ON s.supplier_id = sm.supplier_id
                JOIN contracts c ON c.contract_id = sm.contract_id
                WHERE sm.material_id = :materialId
                  AND (:erpSupplierId IS NULL OR s.erp_supplier_id = :erpSupplierId)
                  AND sm.valid_from <= :asOfDate
                  AND (sm.valid_to IS NULL OR sm.valid_to >= :asOfDate)
                ORDER BY
                    CASE WHEN :erpSupplierId IS NOT NULL AND s.erp_supplier_id = :erpSupplierId THEN 0 ELSE 1 END,
                    sm.priority_rank
                LIMIT 1
                """, params, (rs, rowNum) -> new SupplyRow(
                rs.getLong("supplier_id"),
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_status"),
                rs.getString("feoc_status"),
                rs.getLong("contract_id"),
                rs.getString("erp_contract_id"),
                rs.getBigDecimal("supply_share_ratio"))));
    }

    public String findAlternativeSupplierStatus(
            long materialId, long primarySupplierId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("primarySupplierId", primarySupplierId)
                .addValue("asOfDate", asOfDate);
        List<String> statuses = jdbc.query("""
                SELECT sm.approved_status
                FROM supplier_materials sm
                JOIN suppliers s ON s.supplier_id = sm.supplier_id
                WHERE sm.material_id = :materialId
                  AND sm.supplier_id <> :primarySupplierId
                  AND sm.is_alternative = TRUE
                  AND s.supplier_status = 'ACTIVE'
                  AND sm.valid_from <= :asOfDate
                  AND (sm.valid_to IS NULL OR sm.valid_to >= :asOfDate)
                  AND sm.approved_status IN ('APPROVED', 'CONDITIONAL')
                ORDER BY CASE sm.approved_status WHEN 'APPROVED' THEN 0 ELSE 1 END, sm.priority_rank
                LIMIT 1
                """, params, (rs, rowNum) -> rs.getString("approved_status"));
        return statuses.isEmpty() ? "NONE" : statuses.get(0);
    }

    public Optional<InboundRow> findNextInbound(long materialId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("asOfDate", asOfDate);
        return first(jdbc.query("""
                SELECT
                    COALESCE(poi.confirmed_arrival_date, poi.expected_arrival_date) AS effective_arrival_date,
                    po.order_status
                FROM purchase_order_items poi
                JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id
                WHERE poi.material_id = :materialId
                  AND po.order_status <> 'CLOSED'
                  AND po.order_date <= :asOfDate
                  AND poi.ordered_quantity > poi.received_quantity
                  AND COALESCE(poi.confirmed_arrival_date, poi.expected_arrival_date) IS NOT NULL
                  AND COALESCE(poi.confirmed_arrival_date, poi.expected_arrival_date) >= :asOfDate
                ORDER BY COALESCE(poi.confirmed_arrival_date, poi.expected_arrival_date), poi.purchase_order_item_id
                LIMIT 1
                """, params, (rs, rowNum) -> new InboundRow(
                rs.getObject("effective_arrival_date", LocalDate.class),
                rs.getString("order_status"))));
    }

    public BigDecimal sumRemainingQuantity(long materialId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("asOfDate", asOfDate);
        BigDecimal result = jdbc.queryForObject("""
                SELECT COALESCE(SUM(poi.ordered_quantity - poi.received_quantity), 0)
                FROM purchase_order_items poi
                JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id
                WHERE poi.material_id = :materialId
                  AND po.order_status <> 'CLOSED'
                  AND po.order_date <= :asOfDate
                  AND poi.ordered_quantity > poi.received_quantity
                """, params, BigDecimal.class);
        return result == null ? BigDecimal.ZERO : result;
    }

    public Optional<Long> resolveMaterialId(String erpMaterialId) {
        return resolveId("materials", "material_id", "erp_material_id", erpMaterialId);
    }

    public Optional<Long> resolveSupplierId(String erpSupplierId) {
        return resolveId("suppliers", "supplier_id", "erp_supplier_id", erpSupplierId);
    }

    public Optional<Long> resolveWarehouseId(String erpWarehouseId) {
        return resolveId("warehouses", "warehouse_id", "erp_warehouse_id", erpWarehouseId);
    }

    public Optional<Long> resolveContractId(String erpContractId) {
        return resolveId("contracts", "contract_id", "erp_contract_id", erpContractId);
    }

    public Optional<Long> resolvePurchaseOrderId(String erpPurchaseOrderId) {
        return resolveId("purchase_orders", "purchase_order_id", "erp_purchase_order_id", erpPurchaseOrderId);
    }

    public Optional<Long> resolvePurchaseOrderItemId(String erpPurchaseOrderItemId) {
        return resolveId(
                "purchase_order_items", "purchase_order_item_id",
                "erp_purchase_order_item_id", erpPurchaseOrderItemId);
    }

    private Optional<Long> resolveId(String table, String pkColumn, String erpColumn, String erpId) {
        String value = blankToNull(erpId);
        if (value == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + pkColumn + " FROM " + table + " WHERE " + erpColumn + " = :erpId";
        return first(jdbc.query(sql, new MapSqlParameterSource("erpId", value),
                (rs, rowNum) -> rs.getLong(pkColumn)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public record MaterialRow(long materialId, String erpMaterialId, String materialName, String unit) {}

    public record InventoryRow(
            BigDecimal onHandQuantity,
            BigDecimal reservedQuantity,
            BigDecimal blockedQuantity,
            BigDecimal qualityHoldQuantity,
            BigDecimal safetyStockQuantity,
            int rowCount,
            int invalidCount
    ) {}

    public record ConsumptionRow(
            BigDecimal averageDailyUsage,
            int rowCount,
            int missingUsageCount,
            int invalidCount
    ) {}

    public record SupplyRow(
            long supplierId,
            String erpSupplierId,
            String supplierStatus,
            String feocStatus,
            long contractId,
            String erpContractId,
            BigDecimal dependencyRatio
    ) {}

    public record InboundRow(LocalDate effectiveArrivalDate, String orderStatus) {}
}

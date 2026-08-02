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

    /** F9: 자격 조건을 통과한 대체 공급사 후보만 반환합니다. 자격 미달 후보는 여기서 제거합니다. */
    public List<AlternativeSupplierRow> findEligibleAlternativeSuppliers(
            long materialId, long primarySupplierId, LocalDate asOfDate, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("primarySupplierId", primarySupplierId)
                .addValue("asOfDate", asOfDate)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT
                    s.erp_supplier_id,
                    s.supplier_name,
                    sm.approved_status,
                    s.feoc_status,
                    s.iatf_16949_certified,
                    s.ppap_approved,
                    sm.lead_time_days,
                    sm.available_capacity_quantity
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
                LIMIT :limit
                """, params, (rs, rowNum) -> new AlternativeSupplierRow(
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("approved_status"),
                rs.getString("feoc_status"),
                rs.getBoolean("iatf_16949_certified"),
                rs.getBoolean("ppap_approved"),
                nullableInt(rs, "lead_time_days"),
                rs.getBigDecimal("available_capacity_quantity")));
    }

    public List<PurchaseOrderRow> findOpenPurchaseOrders(
        long materialId,
        LocalDate asOfDate
) {
    MapSqlParameterSource params =
            new MapSqlParameterSource()
                    .addValue("materialId", materialId)
                    .addValue("asOfDate", asOfDate);

    return jdbc.query("""
            SELECT
                poi.erp_purchase_order_item_id,
                po.erp_purchase_order_id,
                m.erp_material_id,
                s.erp_supplier_id,
                c.erp_contract_id,
                poi.ordered_quantity
                    - poi.received_quantity
                    AS remaining_quantity,
                po.order_status,
                COALESCE(
                    poi.confirmed_arrival_date,
                    poi.expected_arrival_date
                ) AS effective_arrival_date
            FROM purchase_order_items poi
            JOIN purchase_orders po
              ON po.purchase_order_id =
                 poi.purchase_order_id
            JOIN materials m
              ON m.material_id = poi.material_id
            JOIN suppliers s
              ON s.supplier_id = po.supplier_id
            JOIN contracts c
              ON c.contract_id = poi.contract_id
            WHERE poi.material_id = :materialId
              AND po.order_status <> 'CLOSED'
              AND po.order_date <= :asOfDate
              AND poi.ordered_quantity
                    > poi.received_quantity
            ORDER BY
                COALESCE(
                    poi.confirmed_arrival_date,
                    poi.expected_arrival_date
                ) NULLS LAST,
                poi.purchase_order_item_id
            """,
            params,
            (rs, rowNum) -> new PurchaseOrderRow(
                    rs.getString(
                            "erp_purchase_order_item_id"
                    ),
                    rs.getString(
                            "erp_purchase_order_id"
                    ),
                    rs.getString("erp_material_id"),
                    rs.getString("erp_supplier_id"),
                    rs.getString("erp_contract_id"),
                    rs.getBigDecimal(
                            "remaining_quantity"
                    ),
                    rs.getString("order_status"),
                    rs.getObject(
                            "effective_arrival_date",
                            LocalDate.class
                    )
            )
    );
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

    /**
     * 자재 대분류에 속한 활성 ERP 자재 목록.
     *
     * <p>{@code analyses}는 대분류({@code material_category})만 채우고 {@code material_id}는
     * 비워두므로(실데이터에서 전부 NULL — GDELT 기사에 우리 자재 코드가 없음, LLM이 "리튬"까지는
     * 뽑아도 탄산리튬인지 수산화리튬인지는 기사에 없다), 스케줄러가 분석 한 건을 실제 ERP 자재로
     * 펼칠 때 이 조회가 필요하다. 대분류당 1~2개다 — LITHIUM(탄산/수산화)과 GRAPHITE(천연/인조)만 2개.
     */
    public List<String> findActiveErpMaterialIds(String materialCategory) {
        return jdbc.query("""
                SELECT erp_material_id
                FROM materials
                WHERE material_category = :materialCategory AND active = TRUE
                ORDER BY erp_material_id
                """, new MapSqlParameterSource("materialCategory", materialCategory),
                (rs, rowNumber) -> rs.getString("erp_material_id"));
    }

    public Optional<Long> resolveMaterialId(String erpMaterialId) {
        return resolveId("materials", "material_id", "erp_material_id", erpMaterialId);
    }

    /**
     * KG 리졸버 kg_service /resolve가 요구하는 자재 대분류(LITHIUM/COBALT/...).
     *
     * <p>{@code analysis_id} 경로가 아니라 외부신호를 요청 본문에 직접 실은 경우
     * externalSignal.materialCategory()가 null이라 아웃바운드 계약 리졸브가 조용히
     * 건너뛰어졌다(2026-07-31 실증 — 콩고+코발트를 직접 호출해도 아웃바운드 배상책임이
     * 한 번도 안 잡혔음). 이미 알고 있는 material_id로 조회하는 폴백이라 추가 호출 없이 해결된다.
     */
    public Optional<String> findMaterialCategory(long materialId) {
        return first(jdbc.query("""
                SELECT material_category FROM materials WHERE material_id = :materialId
                """, new MapSqlParameterSource("materialId", materialId),
                (rs, rowNum) -> rs.getString("material_category")));
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

    /**
     * 계약서 업로드에서 다음 CTR-XXX를 채번할 때 쓴다(계약서 업로드 → CTR-XXX 자동 발급 작업).
     * "CTR-" 접두어 + 3자리 숫자 형식(예: CTR-028)인 것만 보고 가장 큰 숫자를 반환한다
     * (예: 28). 그 형식을 벗어나는 erp_contract_id는 무시한다.
     */
    public Optional<Integer> findMaxContractSequence() {
        List<String> ids = jdbc.query(
                "SELECT erp_contract_id FROM contracts WHERE erp_contract_id ~ '^CTR-[0-9]{3}$'",
                new MapSqlParameterSource(), (rs, rowNum) -> rs.getString("erp_contract_id"));
        return ids.stream()
                .map(id -> Integer.valueOf(id.substring(4)))
                .max(Integer::compareTo);
    }

    /**
     * 새 공급관계(supplier_materials) 행을 만들 때 SM-XXX를 채번한다.
     * {@link #findMaxContractSequence()}와 같은 방식(SM- 접두어 + 3자리 숫자).
     */
    public Optional<Integer> findMaxSupplierMaterialSequence() {
        List<String> ids = jdbc.query(
                "SELECT erp_supplier_material_id FROM supplier_materials"
                        + " WHERE erp_supplier_material_id ~ '^SM-[0-9]{3}$'",
                new MapSqlParameterSource(), (rs, rowNum) -> rs.getString("erp_supplier_material_id"));
        return ids.stream()
                .map(id -> Integer.valueOf(id.substring(3)))
                .max(Integer::compareTo);
    }

    /** 계약 내부 PK + erp_contract_id를 함께 담는다(계약서 업로드에서 기존 계약 재사용 판단용). */
    public record ContractRef(long contractId, String erpContractId) {}

    /**
     * 이 공급사+자재 조합에 이미 연결된 계약이 있으면 그 계약을 반환한다(어느 계약이든 하나,
     * 우선순위 낮은 것 우선). 계약서 업로드에서 "이미 계약이 있으면 새로 안 만들고 문서만 추가"
     * 판단에 쓴다.
     */
    public Optional<ContractRef> findContractForSupplierMaterial(long supplierId, long materialId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("supplierId", supplierId)
                .addValue("materialId", materialId);
        return first(jdbc.query("""
                SELECT sm.contract_id, c.erp_contract_id
                FROM supplier_materials sm
                JOIN contracts c ON c.contract_id = sm.contract_id
                WHERE sm.supplier_id = :supplierId AND sm.material_id = :materialId
                ORDER BY sm.priority_rank NULLS LAST
                LIMIT 1
                """, params, (rs, rowNum) -> new ContractRef(
                rs.getLong("contract_id"), rs.getString("erp_contract_id"))));
    }

    // --- 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 전용, 인바운드 테이블과 완전히 분리 ---

    public Optional<Long> resolveProductId(String erpProductId) {
        return resolveId("products", "product_id", "erp_product_id", erpProductId);
    }

    public Optional<Long> resolveCustomerId(String erpCustomerId) {
        return resolveId("customers", "customer_id", "erp_customer_id", erpCustomerId);
    }

    public Optional<Long> resolveOutboundContractId(String erpOutboundContractId) {
        return resolveId(
                "outbound_contracts", "outbound_contract_id",
                "erp_outbound_contract_id", erpOutboundContractId);
    }

    /**
     * 아웃바운드 계약서 업로드에서 다음 CTR-OUT-XXX를 채번할 때 쓴다. "CTR-OUT-" 접두어(8자) +
     * 3자리 숫자 형식(예: CTR-OUT-028)인 것만 보고 가장 큰 숫자를 반환한다.
     * {@link #findMaxContractSequence()}의 아웃바운드 버전.
     */
    public Optional<Integer> findMaxOutboundContractSequence() {
        List<String> ids = jdbc.query(
                "SELECT erp_outbound_contract_id FROM outbound_contracts"
                        + " WHERE erp_outbound_contract_id ~ '^CTR-OUT-[0-9]{3}$'",
                new MapSqlParameterSource(), (rs, rowNum) -> rs.getString("erp_outbound_contract_id"));
        return ids.stream()
                .map(id -> Integer.valueOf(id.substring(8)))
                .max(Integer::compareTo);
    }

    /** 아웃바운드 계약 내부 PK + erp_outbound_contract_id를 함께 담는다. */
    public record OutboundContractRef(long outboundContractId, String erpOutboundContractId) {}

    /**
     * 이 제품+고객사 조합에 이미 계약이 있으면 반환한다(어느 계약이든 하나). 인바운드와 달리
     * outbound_contracts가 product_id/customer_id를 직접 갖고 있어 supplier_materials 같은
     * 별도 junction 테이블 조인이 필요 없다.
     */
    public Optional<OutboundContractRef> findOutboundContractForProductCustomer(
            long productId, long customerId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("customerId", customerId);
        return first(jdbc.query("""
                SELECT outbound_contract_id, erp_outbound_contract_id
                FROM outbound_contracts
                WHERE product_id = :productId AND customer_id = :customerId
                ORDER BY outbound_contract_id
                LIMIT 1
                """, params, (rs, rowNum) -> new OutboundContractRef(
                rs.getLong("outbound_contract_id"), rs.getString("erp_outbound_contract_id"))));
    }

    /** 재무 노출도 순위를 매긴 아웃바운드 계약 1건(내부 PK + product/customer). */
    public record RankedOutboundContract(long outboundContractId, long productId, long customerId) {}

    /**
     * KG가 재고부족 확정 시 돌려주는 아웃바운드 계약 외부ID 목록(수십 건일 수 있음) 중,
     * "이 계약이 실제로 얼마짜리 배상 리스크인지"를 근사하는 재무 노출도 상위 {@code limit}건만
     * 골라 내부 PK로 리졸브한다. 전부 검색·브리핑에 실으면 비용·가독성이 감당 안 돼서
     * (2026-07-31 실측: 콩고+코발트 21건) 상위 N건만 상세 취급하는 정책(사용자 확정).
     *
     * <p>노출점수 = 계약가치(quantity_gwh × 1,000,000 × unit_price_usd_kwh) × (penalty_pct/100)
     * + COALESCE(line_stop_charge_usd, 0). "지연 위약금 명목가치 + Line-Stop Charge 고정액"의
     * 근사치로, 절대적인 예측이 아니라 여러 계약 중 상세 검색 우선순위를 매기는 상대적 지표다.
     */
    public List<RankedOutboundContract> findTopOutboundContractsByExposure(
            List<String> erpOutboundContractIds, int limit) {
        if (erpOutboundContractIds == null || erpOutboundContractIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpIds", erpOutboundContractIds)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT outbound_contract_id, product_id, customer_id
                FROM outbound_contracts
                WHERE erp_outbound_contract_id IN (:erpIds)
                ORDER BY
                    (quantity_gwh * 1000000 * unit_price_usd_kwh * (penalty_pct / 100.0))
                    + COALESCE(line_stop_charge_usd, 0) DESC
                LIMIT :limit
                """, params, (rs, rowNum) -> new RankedOutboundContract(
                rs.getLong("outbound_contract_id"), rs.getLong("product_id"), rs.getLong("customer_id")));
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

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 자재 대분류별 조달국 목록. 공개 대시보드 가격 추이의 "국가·지역" 필터가 쓴다.
     *
     * <p>공급사 상태(ACTIVE/UNDER_REVIEW)로 거르지 않는다 — 심사 중인 공급사도 조달 관계는 이미
     * 성립해 있어서, 상태로 걸러내면 인도네시아 니켈·중국 흑연처럼 실제 조달하는 국가가 필터
     * 목록에서 사라진다. 여기서 정책을 만들지 않고 등록된 관계를 그대로 반영한다.
     *
     * <p>공급사명·금액·비중은 포함하지 않는다. 공개 화면이라 "어느 나라에서 조달하는가"까지만
     * 나가고 조달 구조의 세부(누구에게서 얼마나)는 노출하지 않는다.
     */
    public List<MaterialCountryRow> findMaterialSourcingCountries() {
        return jdbc.query("""
                SELECT DISTINCT m.material_category, s.country_code
                FROM supplier_materials sm
                JOIN suppliers s ON s.supplier_id = sm.supplier_id
                JOIN materials m ON m.material_id = sm.material_id
                WHERE s.country_code IS NOT NULL
                ORDER BY m.material_category, s.country_code
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new MaterialCountryRow(
                        rs.getString("material_category"), rs.getString("country_code")));
    }

    public record MaterialCountryRow(String materialCategory, String countryCode) {}

    /**
     * 공급사 국적·결제통화별 발주 금액 합계. 공개 대시보드 "수입 의존도" 도넛의 원천이다.
     *
     * <p>통화별로 나눠서 반환하는 이유: {@code purchase_orders.currency}가 주문마다 USD/EUR/KRW로
     * 달라, 여기서 합쳐버리면 서로 다른 통화를 더한 값이 나온다. 원화 환산은 환율을 아는
     * 서비스 계층에서 한다.
     *
     * <p>{@code order_date} 구간으로 자르지 않는다 — 시드 발주가 5개월치뿐이라 기간을 좁히면
     * 표본이 급격히 줄어든다. 기간 필터가 필요해지면 파라미터로 받는다.
     */
    public List<CountryPurchaseAmountRow> aggregatePurchaseAmountsByCountry() {
        return jdbc.query("""
                SELECT s.country_code,
                       po.currency,
                       SUM(poi.ordered_quantity * poi.unit_price) AS amount,
                       MIN(po.order_date) AS first_order_date,
                       MAX(po.order_date) AS last_order_date
                FROM purchase_order_items poi
                JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id
                JOIN suppliers s ON s.supplier_id = po.supplier_id
                WHERE s.country_code IS NOT NULL
                GROUP BY s.country_code, po.currency
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new CountryPurchaseAmountRow(
                        rs.getString("country_code"),
                        rs.getString("currency"),
                        rs.getBigDecimal("amount"),
                        rs.getDate("first_order_date").toLocalDate(),
                        rs.getDate("last_order_date").toLocalDate()));
    }

    public record CountryPurchaseAmountRow(
            String countryCode,
            String currency,
            BigDecimal amount,
            LocalDate firstOrderDate,
            LocalDate lastOrderDate) {}

    /**
     * 공급사·결제통화별 발주 금액 합계. 대시보드 "공급사 현황"의 의존도 %가 여기서 나온다.
     *
     * <p>{@link #aggregatePurchaseAmountsByCountry()}를 공급사 단위로 내린 것이며, 통화를 합치지
     * 않고 그대로 내보내는 이유도 같다 — 주문마다 통화가 달라 여기서 더하면 서로 다른 화폐를
     * 더한 값이 된다. 원화 환산(고시 매매기준율)은 환율을 아는 서비스 계층이 한다.
     */
    public List<SupplierPurchaseAmountRow> aggregatePurchaseAmountsBySupplier() {
        return jdbc.query("""
                SELECT s.supplier_id, s.supplier_code, s.supplier_name, s.country_code,
                       s.supplier_status, s.risk_level,
                       po.currency,
                       SUM(poi.ordered_quantity * poi.unit_price) AS amount
                FROM purchase_order_items poi
                JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id
                JOIN suppliers s ON s.supplier_id = po.supplier_id
                GROUP BY s.supplier_id, s.supplier_code, s.supplier_name, s.country_code,
                         s.supplier_status, s.risk_level, po.currency
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new SupplierPurchaseAmountRow(
                        rs.getLong("supplier_id"),
                        rs.getString("supplier_code"),
                        rs.getString("supplier_name"),
                        rs.getString("country_code"),
                        rs.getString("supplier_status"),
                        rs.getString("risk_level"),
                        rs.getString("currency"),
                        rs.getBigDecimal("amount")));
    }

    public record SupplierPurchaseAmountRow(
            long supplierId,
            String supplierCode,
            String supplierName,
            String countryCode,
            String supplierStatus,
            String riskLevel,
            String currency,
            BigDecimal amount) {}

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

    public record AlternativeSupplierRow(
            String erpSupplierId,
            String supplierName,
            String approvedStatus,
            String feocStatus,
            boolean iatf16949Certified,
            boolean ppapApproved,
            Integer leadTimeDays,
            java.math.BigDecimal availableCapacityQuantity
    ) {}

    public record PurchaseOrderRow(
        String erpPurchaseOrderItemId,
        String erpPurchaseOrderId,
        String erpMaterialId,
        String erpSupplierId,
        String erpContractId,
        BigDecimal remainingQuantity,
        String orderStatus,
        LocalDate effectiveArrivalDate
) {}
}

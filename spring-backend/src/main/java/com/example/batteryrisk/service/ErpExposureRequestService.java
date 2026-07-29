package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.dto.ErpExposureDto.AlternativeSupplierContext;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureRequest;
import com.example.batteryrisk.dto.ErpExposureDto.ExposureResponse;
import com.example.batteryrisk.dto.ErpExposureDto.MaterialContext;
import com.example.batteryrisk.dto.ErpExposureDto.PurchaseOrderContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * F9/F1과 별개로, ERP Exposure Agent({@code POST /api/v1/internal/erp/exposure})를
 * 호출하기 위한 요청 조립 서비스입니다.
 *
 * <p><b>임시 처리(자재 단위 불일치)</b>: ERP Exposure Agent는 특정 자재 1개({@code affectedMaterialId})를
 * 요구하지만, F3의 mock 추출은 대분류(LITHIUM/NICKEL/COBALT 등)만 인식합니다. 그래서 지금은 해당
 * 카테고리 안에서 {@code material_id}가 가장 작은 자재 1개를 대표로 골라서 씁니다 — 카테고리 안에
 * 자재가 여러 개면 정확도가 떨어지는 임시 방편이며, F3 추출이 자재코드 단위까지 잡아내게 되면
 * 이 대표 선택 로직을 없애고 실제 감지된 자재 ID를 그대로 써야 합니다.
 */
@Service
public class ErpExposureRequestService {
    private static final String FASTAPI_EXPOSURE_PATH = "/api/v1/internal/erp/exposure";

    private final JdbcTemplate jdbcTemplate;
    private final RestClient fastApiRestClient;

    public ErpExposureRequestService(JdbcTemplate jdbcTemplate, RestClient fastApiRestClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.fastApiRestClient = fastApiRestClient;
    }

    public ExposureResponse analyzeExposure(ErpExposureDto.ExposureTriggerRequest trigger) {
        Map<String, Object> material = jdbcTemplate.queryForList(
                "SELECT material_id, material_code, material_name, base_unit FROM materials "
                        + "WHERE material_category = ? ORDER BY material_id LIMIT 1",
                trigger.materialCategory()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "material_category에 해당하는 자재가 없습니다: " + trigger.materialCategory()));

        Long materialId = ((Number) material.get("material_id")).longValue();
        String materialCode = (String) material.get("material_code");

        String mappedImpactDomain = "IRRELEVANT".equals(trigger.impactDomain()) ? "OTHER_IRRELEVANT" : trigger.impactDomain();
        boolean erpAnalysisRequired = !"OTHER_IRRELEVANT".equals(mappedImpactDomain);

        MaterialContext materialContext = erpAnalysisRequired
                ? buildMaterialContext(materialId, materialCode, (String) material.get("material_name"),
                        (String) material.get("base_unit"))
                : null;
        List<PurchaseOrderContext> purchaseOrders = erpAnalysisRequired
                ? findPurchaseOrders(materialId, materialCode) : List.of();
        List<AlternativeSupplierContext> alternativeSuppliers = erpAnalysisRequired
                ? findAlternativeSuppliers(materialId) : List.of();

        ExposureRequest request = new ExposureRequest(
                trigger.requestId(), trigger.eventId(), Instant.now(), mappedImpactDomain,
                trigger.externalSignalScore(), trigger.externalSignalLevel(),
                erpAnalysisRequired ? materialCode : null, null, trigger.affectedCountryCode(),
                materialContext != null ? materialContext.primaryContractId() : null,
                trigger.eventSummary(), erpAnalysisRequired, materialContext, purchaseOrders, alternativeSuppliers);

        return fastApiRestClient.post()
                .uri(FASTAPI_EXPOSURE_PATH)
                .body(request)
                .retrieve()
                .body(ExposureResponse.class);
    }

    private MaterialContext buildMaterialContext(Long materialId, String materialCode, String materialName, String unit) {
        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(on_hand_quantity), 0) AS on_hand, "
                        + "COALESCE(SUM(reserved_quantity), 0) AS reserved, "
                        + "COALESCE(SUM(blocked_quantity), 0) AS blocked, "
                        + "COALESCE(SUM(quality_hold_quantity), 0) AS quality_hold, "
                        + "COALESCE(SUM(safety_stock_quantity), 0) AS safety_stock, "
                        + "MAX(snapshot_at) AS snapshot_at "
                        + "FROM inventory_snapshots WHERE material_id = ? AND is_current = true",
                materialId);

        BigDecimal averageDailyUsage = jdbcTemplate.queryForObject(
                "SELECT SUM(average_daily_usage) FROM material_consumptions WHERE material_id = ? AND is_current = true",
                BigDecimal.class, materialId);

        List<Map<String, Object>> primarySupplierRows = jdbcTemplate.queryForList(
                "SELECT s.supplier_code, s.supplier_status, sm.supply_share_ratio, c.contract_number "
                        + "FROM supplier_materials sm "
                        + "JOIN suppliers s ON s.supplier_id = sm.supplier_id "
                        + "LEFT JOIN contracts c ON c.contract_id = sm.contract_id "
                        + "WHERE sm.material_id = ? AND sm.is_alternative = false "
                        + "ORDER BY sm.priority_rank NULLS LAST LIMIT 1",
                materialId);
        Map<String, Object> primarySupplier = primarySupplierRows.isEmpty() ? null : primarySupplierRows.get(0);

        Instant snapshotAt = toInstant(inventory.get("snapshot_at"));

        return new MaterialContext(
                materialCode, materialName, unit,
                (BigDecimal) inventory.get("on_hand"), (BigDecimal) inventory.get("reserved"),
                (BigDecimal) inventory.get("blocked"), (BigDecimal) inventory.get("quality_hold"),
                averageDailyUsage, (BigDecimal) inventory.get("safety_stock"),
                primarySupplier != null ? (BigDecimal) primarySupplier.get("supply_share_ratio") : null,
                null,
                primarySupplier != null ? mapSupplierStatus((String) primarySupplier.get("supplier_status")) : null,
                primarySupplier != null ? (String) primarySupplier.get("supplier_code") : null,
                primarySupplier != null ? (String) primarySupplier.get("contract_number") : null,
                snapshotAt);
    }

    private List<PurchaseOrderContext> findPurchaseOrders(Long materialId, String materialCode) {
        return jdbcTemplate.query(
                "SELECT poi.purchase_order_item_id, po.po_number, poi.purchase_order_id, "
                        + "s.supplier_code, c.contract_number, "
                        + "(poi.ordered_quantity - poi.received_quantity) AS remaining_quantity, "
                        + "po.order_status, poi.expected_arrival_date, poi.confirmed_arrival_date "
                        + "FROM purchase_order_items poi "
                        + "JOIN purchase_orders po ON po.purchase_order_id = poi.purchase_order_id "
                        + "JOIN suppliers s ON s.supplier_id = po.supplier_id "
                        + "LEFT JOIN contracts c ON c.contract_id = poi.contract_id "
                        + "WHERE poi.material_id = ? AND poi.received_quantity < poi.ordered_quantity",
                (rs, rowNum) -> {
                    LocalDate confirmed = rs.getObject("confirmed_arrival_date", LocalDate.class);
                    LocalDate expected = rs.getObject("expected_arrival_date", LocalDate.class);
                    LocalDate effective = confirmed != null ? confirmed : expected;
                    String orderStatus = rs.getString("order_status");
                    return new PurchaseOrderContext(
                            String.valueOf(rs.getLong("purchase_order_item_id")),
                            rs.getString("po_number"),
                            materialCode,
                            rs.getString("supplier_code"),
                            rs.getString("contract_number"),
                            rs.getBigDecimal("remaining_quantity"),
                            orderStatus,
                            effective != null ? effective.toString() : null,
                            !"CLOSED".equals(orderStatus));
                },
                materialId);
    }

    private List<AlternativeSupplierContext> findAlternativeSuppliers(Long materialId) {
        return jdbcTemplate.query(
                "SELECT s.supplier_code, c.contract_number, s.supplier_status, "
                        + "sm.lead_time_days, sm.approved_status "
                        + "FROM supplier_materials sm "
                        + "JOIN suppliers s ON s.supplier_id = sm.supplier_id "
                        + "LEFT JOIN contracts c ON c.contract_id = sm.contract_id "
                        + "WHERE sm.material_id = ? AND sm.is_alternative = true",
                (rs, rowNum) -> new AlternativeSupplierContext(
                        rs.getString("supplier_code"),
                        rs.getString("contract_number"),
                        mapSupplierStatus(rs.getString("supplier_status")),
                        null,
                        (Integer) rs.getObject("lead_time_days"),
                        rs.getString("approved_status")),
                materialId);
    }

    /** 우리 supplier_status(ACTIVE/UNDER_REVIEW/INACTIVE)를 ERP Agent 계약(ACTIVE/UNDER_REVIEW/SUSPENDED/TERMINATED)으로 맞춥니다. */
    private String mapSupplierStatus(String supplierStatus) {
        return "INACTIVE".equals(supplierStatus) ? "SUSPENDED" : supplierStatus;
    }

    private Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        return null;
    }
}

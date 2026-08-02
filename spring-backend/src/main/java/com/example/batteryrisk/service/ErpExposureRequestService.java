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
 * <p><b>자재 선택(2026-08-01 개정)</b>: ERP Exposure Agent는 특정 자재 1개({@code affectedMaterialId})를
 * 요구하지만, F3의 mock 추출은 대분류(LITHIUM/NICKEL/COBALT 등)만 인식합니다. 이제는
 * {@link KgResolverService}로 country+카테고리를 KG에 물어봐서, 재고부족이 실제로 확정된 공급사가
 * 그 카테고리에서 공급하는 구체적 자재를 고릅니다(국가를 반영한 선택). KG가 매칭 못 하거나
 * 재고가 충분하면(=이 카테고리 전체에 실질적 리스크가 없다고 판단되면), 기존 임시 방편(카테고리
 * 안에서 {@code material_id}가 가장 작은 자재)으로 폴백합니다 — 여전히 부정확할 수 있는 경로지만,
 * KG가 "정말 아무것도 안 걸린다"고 판단한 경우라 심각도가 낮을 가능성이 큽니다.
 */
@Service
public class ErpExposureRequestService {
    private static final String FASTAPI_EXPOSURE_PATH = "/api/v1/internal/erp/exposure";

    private final JdbcTemplate jdbcTemplate;
    private final RestClient fastApiRestClient;
    private final KgResolverService kgResolverService;

    public ErpExposureRequestService(
            JdbcTemplate jdbcTemplate, RestClient fastApiRestClient, KgResolverService kgResolverService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fastApiRestClient = fastApiRestClient;
        this.kgResolverService = kgResolverService;
    }

    /**
     * KG가 재고부족을 확정했는지, 확정했다면 어떤 공급사/자재였는지까지 같이 돌려줘서
     * 호출부(AnalysisService)가 Chain B(멀티에이전트 브리핑) 자동 트리거 여부를 판단할 수 있게 한다.
     */
    public ErpExposureAnalysisResult analyzeExposure(ErpExposureDto.ExposureTriggerRequest trigger) {
        KgResolverService.KgResolveResult kgResult = kgResolverService.resolve(
                trigger.affectedCountryCode(), trigger.materialCategory());

        Map<String, Object> material = kgResult.shortageConfirmed()
                ? findMaterialForSupplier(kgResult.resolvedErpSupplierId(), trigger.materialCategory())
                : null;
        boolean kgShortageConfirmed = material != null;
        String resolvedErpSupplierId = kgShortageConfirmed ? kgResult.resolvedErpSupplierId() : null;

        if (material == null) {
            material = jdbcTemplate.queryForList(
                    "SELECT material_id, material_code, material_name, base_unit, erp_material_id "
                            + "FROM materials WHERE material_category = ? ORDER BY material_id LIMIT 1",
                    trigger.materialCategory()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "material_category에 해당하는 자재가 없습니다: " + trigger.materialCategory()));
        }

        Long materialId = ((Number) material.get("material_id")).longValue();
        String materialCode = (String) material.get("material_code");
        String resolvedErpMaterialId = (String) material.get("erp_material_id");

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
                erpAnalysisRequired ? materialCode : null, resolvedErpSupplierId, trigger.affectedCountryCode(),
                materialContext != null ? materialContext.primaryContractId() : null,
                trigger.eventSummary(), erpAnalysisRequired, materialContext, purchaseOrders,
                // requiredQuantity: 이 경로는 ErpService.buildContext를 타지 않아 안전재고 부족분을
                // 모른다. Agent는 없으면 capacity 판정을 "필요량 미상"으로 낮춰 잡는다.
                null,
                alternativeSuppliers);

        ExposureResponse response = fastApiRestClient.post()
                .uri(FASTAPI_EXPOSURE_PATH)
                .body(request)
                .retrieve()
                .body(ExposureResponse.class);

        return new ErpExposureAnalysisResult(
                response, kgShortageConfirmed, resolvedErpSupplierId,
                kgShortageConfirmed ? resolvedErpMaterialId : null);
    }

    /**
     * 멀티에이전트에 넘길 ERP 자재·공급사를 고른다. {@link #analyzeExposure}의 자재 선택 규칙과 같되
     * ERP Exposure Agent를 호출하지 않는, <b>선택만 하는</b> 경로다.
     *
     * <p>사용자가 화면에서 "ERP·계약 영향 분석"을 누르는 경로(리스크 모니터링)를 위한 것이다.
     * 자동 트리거(Chain A→B)는 KG가 재고부족을 확정한 건만 태우지만, 사용자가 직접 누른 요청까지
     * KG 확정을 요구하면 대부분의 뉴스에서 버튼이 아무 일도 하지 않는다 — 그래서 KG가 못 고르면
     * 카테고리 대표 자재({@code material_id} 최소)와 그 주 공급사로 폴백한다.
     *
     * @return 자재·공급사를 정하지 못하면(카테고리에 자재가 없거나 주 공급사 미등록) null
     */
    public ResolvedErpTarget resolveErpTarget(String countryCode, String materialCategory) {
        KgResolverService.KgResolveResult kgResult = kgResolverService.resolve(countryCode, materialCategory);
        if (kgResult.shortageConfirmed()) {
            Map<String, Object> kgMaterial =
                    findMaterialForSupplier(kgResult.resolvedErpSupplierId(), materialCategory);
            if (kgMaterial != null) {
                return new ResolvedErpTarget(
                        (String) kgMaterial.get("erp_material_id"), kgResult.resolvedErpSupplierId(), true);
            }
        }

        List<Map<String, Object>> fallback = jdbcTemplate.queryForList(
                "SELECT m.erp_material_id, s.erp_supplier_id "
                        + "FROM materials m "
                        + "JOIN supplier_materials sm ON sm.material_id = m.material_id "
                        + "AND sm.is_alternative = false "
                        + "JOIN suppliers s ON s.supplier_id = sm.supplier_id "
                        + "WHERE m.material_category = ? AND m.erp_material_id IS NOT NULL "
                        + "ORDER BY m.material_id, sm.priority_rank NULLS LAST LIMIT 1",
                materialCategory);
        if (fallback.isEmpty()) {
            return null;
        }
        return new ResolvedErpTarget(
                (String) fallback.get(0).get("erp_material_id"),
                (String) fallback.get(0).get("erp_supplier_id"),
                false);
    }

    /**
     * @param kgMatched KG가 국가까지 반영해 고른 조합인지. false면 카테고리 대표 자재 폴백이라
     *                  결과 해석 시 "이 국가와 실제로 연결됐는지는 확인되지 않았다"는 뜻이다.
     */
    public record ResolvedErpTarget(String erpMaterialId, String erpSupplierId, boolean kgMatched) {}

    /**
     * KG가 확정한 공급사가 이 카테고리에서 실제로 공급하는 구체적 자재를 찾는다.
     * 없으면(그래프상 매칭됐지만 ERP DB엔 그 조합이 없는 경우 — 정상적으론 안 나와야 하지만 방어적으로)
     * null을 돌려줘서 호출부가 기존 임시 방편으로 폴백하게 한다.
     */
    private Map<String, Object> findMaterialForSupplier(String erpSupplierId, String materialCategory) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT m.material_id, m.material_code, m.material_name, m.base_unit, m.erp_material_id "
                        + "FROM supplier_materials sm "
                        + "JOIN suppliers s ON s.supplier_id = sm.supplier_id "
                        + "JOIN materials m ON m.material_id = sm.material_id "
                        + "WHERE s.erp_supplier_id = ? AND m.material_category = ? "
                        + "ORDER BY sm.priority_rank NULLS LAST LIMIT 1",
                erpSupplierId, materialCategory);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** KG 매칭 결과를 호출부(AnalysisService)까지 전달하기 위한 반환 타입. */
    public record ErpExposureAnalysisResult(
            ExposureResponse response, boolean kgShortageConfirmed,
            String resolvedErpSupplierId, String resolvedErpMaterialId) {}

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

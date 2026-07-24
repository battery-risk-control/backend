package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpAdminDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.ErpRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** ERP 10개 엔티티를 외부 ERP ID 기준으로 단건 Upsert 합니다. 대시보드의 일일 갱신 입력을 처리합니다. */
@Service
public class ErpAdminService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ErpRepository repository;

    public ErpAdminService(NamedParameterJdbcTemplate jdbc, ErpRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertMaterial(ErpAdminDto.MaterialUpsertRequest request) {
        boolean existed = repository.resolveMaterialId(request.erpMaterialId()).isPresent();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpMaterialId", request.erpMaterialId())
                .addValue("materialCode", request.materialCode())
                .addValue("materialName", request.materialName())
                .addValue("materialCategory", request.materialCategory())
                .addValue("baseUnit", request.baseUnit())
                .addValue("criticality", request.criticality())
                .addValue("active", request.active())
                .addValue("erpGroupCode", request.erpGroupCode());
        jdbc.update("""
                INSERT INTO materials (
                    erp_material_id, material_code, material_name, material_category,
                    base_unit, criticality, active, erp_group_code, created_at, updated_at
                ) VALUES (
                    :erpMaterialId, :materialCode, :materialName, :materialCategory,
                    :baseUnit, :criticality, :active, :erpGroupCode, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (erp_material_id) DO UPDATE SET
                    material_code = EXCLUDED.material_code,
                    material_name = EXCLUDED.material_name,
                    material_category = EXCLUDED.material_category,
                    base_unit = EXCLUDED.base_unit,
                    criticality = EXCLUDED.criticality,
                    active = EXCLUDED.active,
                    erp_group_code = EXCLUDED.erp_group_code,
                    updated_at = CURRENT_TIMESTAMP
                """, params);
        Long id = repository.resolveMaterialId(request.erpMaterialId()).orElseThrow();
        return response("materials", request.erpMaterialId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertSupplier(ErpAdminDto.SupplierUpsertRequest request) {
        boolean existed = repository.resolveSupplierId(request.erpSupplierId()).isPresent();
        String certifications = request.certifications();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpSupplierId", request.erpSupplierId())
                .addValue("supplierCode", request.supplierCode())
                .addValue("supplierName", request.supplierName())
                .addValue("countryCode", request.countryCode())
                .addValue("supplierStatus", request.supplierStatus())
                .addValue("riskLevel", request.riskLevel())
                .addValue("feocStatus", request.feocStatus() ? "YES" : "NO")
                .addValue("certifications", certifications)
                .addValue("iatf", certifications.contains("IATF16949"))
                .addValue("ppap", certifications.contains("PPAP"))
                .addValue("active", !"INACTIVE".equals(request.supplierStatus()));
        jdbc.update("""
                INSERT INTO suppliers (
                    erp_supplier_id, supplier_code, supplier_name, country_code,
                    supplier_status, risk_level, feoc_status, certifications,
                    iatf_16949_certified, ppap_approved, active, created_at, updated_at
                ) VALUES (
                    :erpSupplierId, :supplierCode, :supplierName, :countryCode,
                    :supplierStatus, :riskLevel, :feocStatus, :certifications,
                    :iatf, :ppap, :active, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (erp_supplier_id) DO UPDATE SET
                    supplier_code = EXCLUDED.supplier_code,
                    supplier_name = EXCLUDED.supplier_name,
                    country_code = EXCLUDED.country_code,
                    supplier_status = EXCLUDED.supplier_status,
                    risk_level = EXCLUDED.risk_level,
                    feoc_status = EXCLUDED.feoc_status,
                    certifications = EXCLUDED.certifications,
                    iatf_16949_certified = EXCLUDED.iatf_16949_certified,
                    ppap_approved = EXCLUDED.ppap_approved,
                    active = EXCLUDED.active,
                    updated_at = CURRENT_TIMESTAMP
                """, params);
        Long id = repository.resolveSupplierId(request.erpSupplierId()).orElseThrow();
        return response("suppliers", request.erpSupplierId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertWarehouse(ErpAdminDto.WarehouseUpsertRequest request) {
        boolean existed = repository.resolveWarehouseId(request.erpWarehouseId()).isPresent();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpWarehouseId", request.erpWarehouseId())
                .addValue("warehouseCode", request.warehouseCode())
                .addValue("warehouseName", request.warehouseName())
                .addValue("countryCode", request.countryCode())
                .addValue("timezone", request.timezone())
                .addValue("warehouseType", request.warehouseType())
                .addValue("active", request.active());
        jdbc.update("""
                INSERT INTO warehouses (
                    erp_warehouse_id, warehouse_code, warehouse_name, country_code,
                    timezone, warehouse_type, active
                ) VALUES (
                    :erpWarehouseId, :warehouseCode, :warehouseName, :countryCode,
                    :timezone, :warehouseType, :active
                )
                ON CONFLICT (erp_warehouse_id) DO UPDATE SET
                    warehouse_code = EXCLUDED.warehouse_code,
                    warehouse_name = EXCLUDED.warehouse_name,
                    country_code = EXCLUDED.country_code,
                    timezone = EXCLUDED.timezone,
                    warehouse_type = EXCLUDED.warehouse_type,
                    active = EXCLUDED.active
                """, params);
        Long id = repository.resolveWarehouseId(request.erpWarehouseId()).orElseThrow();
        return response("warehouses", request.erpWarehouseId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertContract(ErpAdminDto.ContractUpsertRequest request) {
        boolean existed = repository.resolveContractId(request.erpContractId()).isPresent();
        long supplierId = requireFk(repository.resolveSupplierId(request.erpSupplierId()),
                ErrorCode.ERP_SUPPLIER_NOT_FOUND);
        long materialId = requireFk(repository.resolveMaterialId(request.erpMaterialId()),
                ErrorCode.ERP_MATERIAL_NOT_FOUND);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpContractId", request.erpContractId())
                .addValue("contractNumber", request.contractNumber())
                .addValue("supplierId", supplierId)
                .addValue("materialId", materialId)
                .addValue("contractName", request.contractName())
                .addValue("contractStatus", request.contractStatus())
                .addValue("effectiveDate", request.effectiveDate())
                .addValue("expirationDate", request.expirationDate())
                .addValue("documentId", request.documentId())
                .addValue("documentSource", request.documentSource())
                .addValue("documentPath", request.documentPath())
                .addValue("contractRole", request.contractRole())
                .addValue("supplierApprovalStatus", request.supplierApprovalStatus())
                .addValue("indexedAt", request.indexedAt());
        jdbc.update("""
                INSERT INTO contracts (
                    erp_contract_id, contract_number, supplier_id, material_id, contract_name,
                    status, start_date, end_date, document_source, document_path,
                    source_document_id, contract_role, supplier_approval_status,
                    source_indexed_at, updated_at
                ) VALUES (
                    :erpContractId, :contractNumber, :supplierId, :materialId, :contractName,
                    :contractStatus, :effectiveDate, :expirationDate, :documentSource, :documentPath,
                    :documentId, :contractRole, :supplierApprovalStatus,
                    :indexedAt, CURRENT_TIMESTAMP
                )
                ON CONFLICT (erp_contract_id) DO UPDATE SET
                    contract_number = EXCLUDED.contract_number,
                    supplier_id = EXCLUDED.supplier_id,
                    material_id = EXCLUDED.material_id,
                    contract_name = EXCLUDED.contract_name,
                    status = EXCLUDED.status,
                    start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date,
                    document_source = EXCLUDED.document_source,
                    document_path = EXCLUDED.document_path,
                    source_document_id = EXCLUDED.source_document_id,
                    contract_role = EXCLUDED.contract_role,
                    supplier_approval_status = EXCLUDED.supplier_approval_status,
                    source_indexed_at = EXCLUDED.source_indexed_at,
                    updated_at = CURRENT_TIMESTAMP
                """, params);
        Long id = repository.resolveContractId(request.erpContractId()).orElseThrow();
        return response("contracts", request.erpContractId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertSupplierMaterial(ErpAdminDto.SupplierMaterialUpsertRequest request) {
        boolean existed = resolveSupplierMaterialId(request.erpSupplierMaterialId()) != null;
        long supplierId = requireFk(repository.resolveSupplierId(request.erpSupplierId()),
                ErrorCode.ERP_SUPPLIER_NOT_FOUND);
        long materialId = requireFk(repository.resolveMaterialId(request.erpMaterialId()),
                ErrorCode.ERP_MATERIAL_NOT_FOUND);
        long contractId = requireFk(repository.resolveContractId(request.erpContractId()),
                ErrorCode.ERP_CONTRACT_NOT_FOUND);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpSupplierMaterialId", request.erpSupplierMaterialId())
                .addValue("supplierId", supplierId)
                .addValue("materialId", materialId)
                .addValue("contractId", contractId)
                .addValue("supplyShareRatio", request.supplyShareRatio())
                .addValue("leadTimeDays", request.leadTimeDays())
                .addValue("minimumOrderQuantity", request.minimumOrderQuantity())
                .addValue("approvedStatus", request.approvedStatus())
                .addValue("priorityRank", request.priorityRank())
                .addValue("isAlternative", request.isAlternative())
                .addValue("validFrom", request.validFrom())
                .addValue("validTo", request.validTo());
        jdbc.update("""
                INSERT INTO supplier_materials (
                    erp_supplier_material_id, supplier_id, material_id, contract_id,
                    supply_share_ratio, lead_time_days, minimum_order_quantity,
                    approved_status, priority_rank, is_alternative, valid_from, valid_to
                ) VALUES (
                    :erpSupplierMaterialId, :supplierId, :materialId, :contractId,
                    :supplyShareRatio, :leadTimeDays, :minimumOrderQuantity,
                    :approvedStatus, :priorityRank, :isAlternative, :validFrom, :validTo
                )
                ON CONFLICT (erp_supplier_material_id) DO UPDATE SET
                    supplier_id = EXCLUDED.supplier_id,
                    material_id = EXCLUDED.material_id,
                    contract_id = EXCLUDED.contract_id,
                    supply_share_ratio = EXCLUDED.supply_share_ratio,
                    lead_time_days = EXCLUDED.lead_time_days,
                    minimum_order_quantity = EXCLUDED.minimum_order_quantity,
                    approved_status = EXCLUDED.approved_status,
                    priority_rank = EXCLUDED.priority_rank,
                    is_alternative = EXCLUDED.is_alternative,
                    valid_from = EXCLUDED.valid_from,
                    valid_to = EXCLUDED.valid_to
                """, params);
        Long id = resolveSupplierMaterialId(request.erpSupplierMaterialId());
        return response("supplier_materials", request.erpSupplierMaterialId(), id, !existed);
    }

    /** 시점별 스냅샷: 기존 현재 재고를 내리고 새 스냅샷을 추가합니다. */
    @Transactional
    public ErpAdminDto.UpsertResponse upsertInventorySnapshot(ErpAdminDto.InventorySnapshotUpsertRequest request) {
        long materialId = requireFk(repository.resolveMaterialId(request.erpMaterialId()),
                ErrorCode.ERP_MATERIAL_NOT_FOUND);
        long warehouseId = requireFk(repository.resolveWarehouseId(request.erpWarehouseId()),
                ErrorCode.ERP_WAREHOUSE_NOT_FOUND);
        jdbc.update("""
                UPDATE inventory_snapshots SET is_current = FALSE
                WHERE material_id = :materialId AND warehouse_id = :warehouseId AND is_current = TRUE
                """, new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("warehouseId", warehouseId));
        String erpId = "INV-" + shortUuid();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpId", erpId)
                .addValue("materialId", materialId)
                .addValue("warehouseId", warehouseId)
                .addValue("onHand", request.onHandQuantity())
                .addValue("reserved", request.reservedQuantity())
                .addValue("blocked", request.blockedQuantity())
                .addValue("qualityHold", request.qualityHoldQuantity())
                .addValue("safetyStock", request.safetyStockQuantity())
                .addValue("sourceUnit", request.sourceUnit())
                .addValue("normalizedUnit", request.normalizedUnit())
                .addValue("dataQualityFlag", request.dataQualityFlag());
        jdbc.update("""
                INSERT INTO inventory_snapshots (
                    erp_inventory_snapshot_id, material_id, warehouse_id, on_hand_quantity,
                    reserved_quantity, blocked_quantity, quality_hold_quantity, safety_stock_quantity,
                    snapshot_at, is_current, source_unit, normalized_unit, data_quality_flag
                ) VALUES (
                    :erpId, :materialId, :warehouseId, :onHand,
                    :reserved, :blocked, :qualityHold, :safetyStock,
                    CURRENT_TIMESTAMP, TRUE, :sourceUnit, :normalizedUnit, :dataQualityFlag
                )
                """, params);
        Long id = single("SELECT inventory_snapshot_id FROM inventory_snapshots"
                + " WHERE erp_inventory_snapshot_id = :erpId", erpId);
        return response("inventory_snapshots", erpId, id, true);
    }

    /** 시점별 스냅샷: 기존 현재 소비량을 내리고 새 소비량을 추가합니다. */
    @Transactional
    public ErpAdminDto.UpsertResponse upsertMaterialConsumption(ErpAdminDto.MaterialConsumptionUpsertRequest request) {
        long materialId = requireFk(repository.resolveMaterialId(request.erpMaterialId()),
                ErrorCode.ERP_MATERIAL_NOT_FOUND);
        jdbc.update("""
                UPDATE material_consumptions SET is_current = FALSE
                WHERE material_id = :materialId AND plant_code = :plantCode AND is_current = TRUE
                """, new MapSqlParameterSource()
                .addValue("materialId", materialId)
                .addValue("plantCode", request.plantCode()));
        String erpId = "CON-" + shortUuid();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpId", erpId)
                .addValue("materialId", materialId)
                .addValue("plantCode", request.plantCode())
                .addValue("averageDailyUsage", request.averageDailyUsage())
                .addValue("calculationWindowDays", request.calculationWindowDays())
                .addValue("dataQualityFlag", request.dataQualityFlag());
        jdbc.update("""
                INSERT INTO material_consumptions (
                    erp_consumption_id, material_id, plant_code, average_daily_usage,
                    calculation_window_days, calculated_at, is_current, data_quality_flag
                ) VALUES (
                    :erpId, :materialId, :plantCode, :averageDailyUsage,
                    :calculationWindowDays, CURRENT_TIMESTAMP, TRUE, :dataQualityFlag
                )
                """, params);
        Long id = single("SELECT consumption_id FROM material_consumptions"
                + " WHERE erp_consumption_id = :erpId", erpId);
        return response("material_consumptions", erpId, id, true);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertPurchaseOrder(ErpAdminDto.PurchaseOrderUpsertRequest request) {
        boolean existed = repository.resolvePurchaseOrderId(request.erpPurchaseOrderId()).isPresent();
        long supplierId = requireFk(repository.resolveSupplierId(request.erpSupplierId()),
                ErrorCode.ERP_SUPPLIER_NOT_FOUND);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpPurchaseOrderId", request.erpPurchaseOrderId())
                .addValue("poNumber", request.poNumber())
                .addValue("supplierId", supplierId)
                .addValue("orderDate", request.orderDate())
                .addValue("currency", request.currency())
                .addValue("orderStatus", request.orderStatus())
                .addValue("transportMode", request.transportMode())
                .addValue("portOfEntry", request.portOfEntry());
        jdbc.update("""
                INSERT INTO purchase_orders (
                    erp_purchase_order_id, po_number, supplier_id, order_date, currency,
                    order_status, transport_mode, port_of_entry, created_at, updated_at
                ) VALUES (
                    :erpPurchaseOrderId, :poNumber, :supplierId, :orderDate, :currency,
                    :orderStatus, :transportMode, :portOfEntry, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                ON CONFLICT (erp_purchase_order_id) DO UPDATE SET
                    po_number = EXCLUDED.po_number,
                    supplier_id = EXCLUDED.supplier_id,
                    order_date = EXCLUDED.order_date,
                    currency = EXCLUDED.currency,
                    order_status = EXCLUDED.order_status,
                    transport_mode = EXCLUDED.transport_mode,
                    port_of_entry = EXCLUDED.port_of_entry,
                    updated_at = CURRENT_TIMESTAMP
                """, params);
        Long id = repository.resolvePurchaseOrderId(request.erpPurchaseOrderId()).orElseThrow();
        return response("purchase_orders", request.erpPurchaseOrderId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertPurchaseOrderItem(ErpAdminDto.PurchaseOrderItemUpsertRequest request) {
        boolean existed = repository.resolvePurchaseOrderItemId(request.erpPurchaseOrderItemId()).isPresent();
        long purchaseOrderId = requireFk(repository.resolvePurchaseOrderId(request.erpPurchaseOrderId()),
                ErrorCode.ERP_PURCHASE_ORDER_NOT_FOUND);
        long materialId = requireFk(repository.resolveMaterialId(request.erpMaterialId()),
                ErrorCode.ERP_MATERIAL_NOT_FOUND);
        long contractId = requireFk(repository.resolveContractId(request.erpContractId()),
                ErrorCode.ERP_CONTRACT_NOT_FOUND);
        long warehouseId = requireFk(repository.resolveWarehouseId(request.erpWarehouseId()),
                ErrorCode.ERP_WAREHOUSE_NOT_FOUND);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpPurchaseOrderItemId", request.erpPurchaseOrderItemId())
                .addValue("purchaseOrderId", purchaseOrderId)
                .addValue("materialId", materialId)
                .addValue("contractId", contractId)
                .addValue("orderedQuantity", request.orderedQuantity())
                .addValue("receivedQuantity", request.receivedQuantity())
                .addValue("unit", request.unit())
                .addValue("expectedArrivalDate", request.expectedArrivalDate())
                .addValue("confirmedArrivalDate", request.confirmedArrivalDate())
                .addValue("unitPrice", request.unitPrice())
                .addValue("incoterm", request.incoterm())
                .addValue("warehouseId", warehouseId);
        jdbc.update("""
                INSERT INTO purchase_order_items (
                    erp_purchase_order_item_id, purchase_order_id, material_id, contract_id,
                    ordered_quantity, received_quantity, unit, expected_arrival_date,
                    confirmed_arrival_date, unit_price, incoterm, destination_warehouse_id
                ) VALUES (
                    :erpPurchaseOrderItemId, :purchaseOrderId, :materialId, :contractId,
                    :orderedQuantity, :receivedQuantity, :unit, :expectedArrivalDate,
                    :confirmedArrivalDate, :unitPrice, :incoterm, :warehouseId
                )
                ON CONFLICT (erp_purchase_order_item_id) DO UPDATE SET
                    purchase_order_id = EXCLUDED.purchase_order_id,
                    material_id = EXCLUDED.material_id,
                    contract_id = EXCLUDED.contract_id,
                    ordered_quantity = EXCLUDED.ordered_quantity,
                    received_quantity = EXCLUDED.received_quantity,
                    unit = EXCLUDED.unit,
                    expected_arrival_date = EXCLUDED.expected_arrival_date,
                    confirmed_arrival_date = EXCLUDED.confirmed_arrival_date,
                    unit_price = EXCLUDED.unit_price,
                    incoterm = EXCLUDED.incoterm,
                    destination_warehouse_id = EXCLUDED.destination_warehouse_id
                """, params);
        Long id = repository.resolvePurchaseOrderItemId(request.erpPurchaseOrderItemId()).orElseThrow();
        return response("purchase_order_items", request.erpPurchaseOrderItemId(), id, !existed);
    }

    @Transactional
    public ErpAdminDto.UpsertResponse upsertGoodsReceipt(ErpAdminDto.GoodsReceiptUpsertRequest request) {
        boolean existed = resolveGoodsReceiptId(request.erpGoodsReceiptId()) != null;
        long purchaseOrderItemId = requireFk(
                repository.resolvePurchaseOrderItemId(request.erpPurchaseOrderItemId()),
                ErrorCode.ERP_PURCHASE_ORDER_ITEM_NOT_FOUND);
        long warehouseId = requireFk(repository.resolveWarehouseId(request.erpWarehouseId()),
                ErrorCode.ERP_WAREHOUSE_NOT_FOUND);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpGoodsReceiptId", request.erpGoodsReceiptId())
                .addValue("purchaseOrderItemId", purchaseOrderItemId)
                .addValue("receivedQuantity", request.receivedQuantity())
                .addValue("receivedAt", request.receivedAt())
                .addValue("warehouseId", warehouseId)
                .addValue("qualityStatus", request.qualityStatus())
                .addValue("batchNumber", request.batchNumber())
                .addValue("qualityReleasedAt", request.qualityReleasedAt());
        jdbc.update("""
                INSERT INTO goods_receipts (
                    erp_goods_receipt_id, purchase_order_item_id, received_quantity,
                    received_at, warehouse_id, quality_status, batch_number, quality_released_at
                ) VALUES (
                    :erpGoodsReceiptId, :purchaseOrderItemId, :receivedQuantity,
                    :receivedAt, :warehouseId, :qualityStatus, :batchNumber, :qualityReleasedAt
                )
                ON CONFLICT (erp_goods_receipt_id) DO UPDATE SET
                    purchase_order_item_id = EXCLUDED.purchase_order_item_id,
                    received_quantity = EXCLUDED.received_quantity,
                    received_at = EXCLUDED.received_at,
                    warehouse_id = EXCLUDED.warehouse_id,
                    quality_status = EXCLUDED.quality_status,
                    batch_number = EXCLUDED.batch_number,
                    quality_released_at = EXCLUDED.quality_released_at
                """, params);
        Long id = resolveGoodsReceiptId(request.erpGoodsReceiptId());
        return response("goods_receipts", request.erpGoodsReceiptId(), id, !existed);
    }

    private Long resolveSupplierMaterialId(String erpId) {
        return single("SELECT supplier_material_id FROM supplier_materials"
                + " WHERE erp_supplier_material_id = :erpId", erpId);
    }

    private Long resolveGoodsReceiptId(String erpId) {
        return single("SELECT goods_receipt_id FROM goods_receipts"
                + " WHERE erp_goods_receipt_id = :erpId", erpId);
    }

    private Long single(String sql, String erpId) {
        return jdbc.query(sql, new MapSqlParameterSource("erpId", erpId),
                        (rs, rowNum) -> rs.getLong(1))
                .stream().findFirst().orElse(null);
    }

    private static long requireFk(Optional<Long> resolved, ErrorCode notFound) {
        return resolved.orElseThrow(() -> new BusinessException(notFound));
    }

    private static ErpAdminDto.UpsertResponse response(
            String entity, String erpId, Long internalId, boolean created) {
        return new ErpAdminDto.UpsertResponse(entity, erpId, internalId, created, OffsetDateTime.now());
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

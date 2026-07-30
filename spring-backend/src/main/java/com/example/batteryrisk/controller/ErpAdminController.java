package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ErpAdminDto;
import com.example.batteryrisk.service.ErpAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ERP 10개 엔티티의 단건 Upsert API입니다. 대시보드에서 일일 ERP 데이터 갱신을 받습니다.
 * 역할 제한 없이 로그인한 모든 계층(PURCHASING/STRATEGY/EXECUTIVE)이 사용할 수 있습니다.
 */
@RestController
@RequestMapping("/api/v1/erp/admin")
@SecurityRequirement(name = "bearerAuth")
public class ErpAdminController {
    private final ErpAdminService service;

    public ErpAdminController(ErpAdminService service) {
        this.service = service;
    }

    @Operation(summary = "자재(materials) Upsert", description = "erp_material_id 기준으로 없으면 생성, 있으면 갱신합니다.")
    @PostMapping("/materials")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertMaterial(
            @Valid @RequestBody ErpAdminDto.MaterialUpsertRequest request) {
        return ApiResponse.ok(service.upsertMaterial(request));
    }

    @Operation(summary = "공급사(suppliers) Upsert")
    @PostMapping("/suppliers")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertSupplier(
            @Valid @RequestBody ErpAdminDto.SupplierUpsertRequest request) {
        return ApiResponse.ok(service.upsertSupplier(request));
    }

    @Operation(summary = "창고(warehouses) Upsert")
    @PostMapping("/warehouses")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertWarehouse(
            @Valid @RequestBody ErpAdminDto.WarehouseUpsertRequest request) {
        return ApiResponse.ok(service.upsertWarehouse(request));
    }

    @Operation(summary = "계약(contracts) Upsert")
    @PostMapping("/contracts")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertContract(
            @Valid @RequestBody ErpAdminDto.ContractUpsertRequest request) {
        return ApiResponse.ok(service.upsertContract(request));
    }

    @Operation(summary = "공급사-자재(supplier_materials) Upsert")
    @PostMapping("/supplier-materials")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertSupplierMaterial(
            @Valid @RequestBody ErpAdminDto.SupplierMaterialUpsertRequest request) {
        return ApiResponse.ok(service.upsertSupplierMaterial(request));
    }

    @Operation(
            summary = "재고 스냅샷(inventory_snapshots) 갱신",
            description = "새 스냅샷을 추가하고 기존 현재 재고를 내립니다. 자재 사용량 반영 등 일일 갱신에 사용합니다.")
    @PostMapping("/inventory-snapshots")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertInventorySnapshot(
            @Valid @RequestBody ErpAdminDto.InventorySnapshotUpsertRequest request) {
        return ApiResponse.ok(service.upsertInventorySnapshot(request));
    }

    @Operation(
            summary = "소비량(material_consumptions) 갱신",
            description = "새 소비량 스냅샷을 추가하고 기존 현재 소비량을 내립니다.")
    @PostMapping("/material-consumptions")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertMaterialConsumption(
            @Valid @RequestBody ErpAdminDto.MaterialConsumptionUpsertRequest request) {
        return ApiResponse.ok(service.upsertMaterialConsumption(request));
    }

    @Operation(summary = "발주(purchase_orders) Upsert")
    @PostMapping("/purchase-orders")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertPurchaseOrder(
            @Valid @RequestBody ErpAdminDto.PurchaseOrderUpsertRequest request) {
        return ApiResponse.ok(service.upsertPurchaseOrder(request));
    }

    @Operation(summary = "발주 품목(purchase_order_items) Upsert")
    @PostMapping("/purchase-order-items")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertPurchaseOrderItem(
            @Valid @RequestBody ErpAdminDto.PurchaseOrderItemUpsertRequest request) {
        return ApiResponse.ok(service.upsertPurchaseOrderItem(request));
    }

    @Operation(summary = "입고(goods_receipts) Upsert")
    @PostMapping("/goods-receipts")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertGoodsReceipt(
            @Valid @RequestBody ErpAdminDto.GoodsReceiptUpsertRequest request) {
        return ApiResponse.ok(service.upsertGoodsReceipt(request));
    }

    @Operation(summary = "완제품(products) Upsert", description = "아웃바운드 전용, 인바운드 테이블과 분리")
    @PostMapping("/products")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertProduct(
            @Valid @RequestBody ErpAdminDto.ProductUpsertRequest request) {
        return ApiResponse.ok(service.upsertProduct(request));
    }

    @Operation(summary = "고객사(customers) Upsert", description = "아웃바운드 전용, 인바운드 테이블과 분리")
    @PostMapping("/customers")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertCustomer(
            @Valid @RequestBody ErpAdminDto.CustomerUpsertRequest request) {
        return ApiResponse.ok(service.upsertCustomer(request));
    }

    @Operation(summary = "아웃바운드 계약(outbound_contracts) Upsert")
    @PostMapping("/outbound-contracts")
    public ApiResponse<ErpAdminDto.UpsertResponse> upsertOutboundContract(
            @Valid @RequestBody ErpAdminDto.OutboundContractUpsertRequest request) {
        return ApiResponse.ok(service.upsertOutboundContract(request));
    }
}

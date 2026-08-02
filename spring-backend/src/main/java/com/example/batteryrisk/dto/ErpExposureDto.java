package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ERP Exposure Agent(FastAPI {@code POST /api/v1/internal/erp/exposure}) 연동 DTO.
 * 이 API는 camelCase JSON을 쓰므로, 프로젝트 전역 SNAKE_CASE 설정을 덮어쓰기 위해 필드마다 {@code @JsonProperty}를 명시합니다.
 */
public class ErpExposureDto {

    public record MaterialContext(
            @JsonProperty("materialId") String materialId,
            @JsonProperty("materialName") String materialName,
            @JsonProperty("unit") String unit,
            @JsonProperty("onHandQuantity") BigDecimal onHandQuantity,
            @JsonProperty("reservedQuantity") BigDecimal reservedQuantity,
            @JsonProperty("blockedQuantity") BigDecimal blockedQuantity,
            @JsonProperty("qualityHoldQuantity") BigDecimal qualityHoldQuantity,
            @JsonProperty("averageDailyUsage") BigDecimal averageDailyUsage,
            @JsonProperty("safetyStockQuantity") BigDecimal safetyStockQuantity,
            @JsonProperty("supplierDependencyRatio") BigDecimal supplierDependencyRatio,
            @JsonProperty("alternativeSupplierStatus") String alternativeSupplierStatus,
            @JsonProperty("supplierStatus") String supplierStatus,
            @JsonProperty("primarySupplierId") String primarySupplierId,
            @JsonProperty("primaryContractId") String primaryContractId,
            @JsonProperty("inventorySnapshotAt") Instant inventorySnapshotAt) {
    }

    public record PurchaseOrderContext(
            @JsonProperty("purchaseOrderItemId") String purchaseOrderItemId,
            @JsonProperty("purchaseOrderId") String purchaseOrderId,
            @JsonProperty("materialId") String materialId,
            @JsonProperty("supplierId") String supplierId,
            @JsonProperty("contractId") String contractId,
            @JsonProperty("remainingQuantity") BigDecimal remainingQuantity,
            @JsonProperty("orderStatus") String orderStatus,
            @JsonProperty("effectiveArrivalDate") String effectiveArrivalDate,
            @JsonProperty("eligibleForEta") boolean eligibleForEta) {
    }

    public record AlternativeSupplierContext(
            @JsonProperty("supplierId") String supplierId,
            @JsonProperty("contractId") String contractId,
            @JsonProperty("supplierStatus") String supplierStatus,
            @JsonProperty("availableCapacityQuantity") BigDecimal availableCapacityQuantity,
            @JsonProperty("leadTimeDays") Integer leadTimeDays,
            @JsonProperty("qualificationStatus") String qualificationStatus) {
    }

    public record ExposureRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("eventId") String eventId,
            @JsonProperty("asOf") Instant asOf,
            @JsonProperty("impactDomain") String impactDomain,
            @JsonProperty("externalSignalScore") double externalSignalScore,
            @JsonProperty("externalSignalLevel") String externalSignalLevel,
            @JsonProperty("affectedMaterialId") String affectedMaterialId,
            @JsonProperty("affectedSupplierId") String affectedSupplierId,
            @JsonProperty("affectedCountryCode") String affectedCountryCode,
            @JsonProperty("primaryContractId") String primaryContractId,
            @JsonProperty("eventSummary") String eventSummary,
            @JsonProperty("erpAnalysisRequired") boolean erpAnalysisRequired,
            @JsonProperty("materialContext") MaterialContext materialContext,
            @JsonProperty("purchaseOrders") List<PurchaseOrderContext> purchaseOrders,
            /**
             * 대체 공급사가 채워줘야 할 수량(= 안전재고 부족분). Agent의 supplier assessment가
             * 후보별 capacity 충족도를 계산할 때 쓴다. 없으면 capacity 판정이 "필요량 미상"이 된다.
             */
            @JsonProperty("requiredQuantity") BigDecimal requiredQuantity,
            @JsonProperty("alternativeSuppliers") List<AlternativeSupplierContext> alternativeSuppliers) {
    }

    /** ERP Agent가 공급사(supplierId=supplier_code)별로 매긴 위험 평가입니다. */
    public record SupplierAssessment(
            @JsonProperty("supplierId") String supplierId,
            @JsonProperty("supplierRiskScore") Double supplierRiskScore,
            @JsonProperty("capacityStatus") String capacityStatus,
            @JsonProperty("reasonCodes") List<String> reasonCodes) {
    }

    public record ExposureResponse(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("affectedMaterialIds") List<String> affectedMaterialIds,
            @JsonProperty("affectedSupplierIds") List<String> affectedSupplierIds,
            @JsonProperty("affectedContractIds") List<String> affectedContractIds,
            @JsonProperty("facts") Map<String, Object> facts,
            @JsonProperty("riskComponents") Map<String, Object> riskComponents,
            @JsonProperty("erpExposureScore") Double erpExposureScore,
            @JsonProperty("supplierAssessments") List<SupplierAssessment> supplierAssessments,
            @JsonProperty("exposureLevel") String exposureLevel,
            @JsonProperty("forcedCritical") boolean forcedCritical,
            @JsonProperty("contractReviewRequired") boolean contractReviewRequired,
            @JsonProperty("questionsForContractAgent") List<Map<String, Object>> questionsForContractAgent,
            @JsonProperty("calculationEvidence") List<Map<String, Object>> calculationEvidence,
            @JsonProperty("dataQualityStatus") String dataQualityStatus,
            @JsonProperty("manualReviewRequired") boolean manualReviewRequired,
            @JsonProperty("warnings") List<String> warnings,
            @JsonProperty("ruleVersion") String ruleVersion) {
    }

    /** Swagger 등에서 수동 호출용으로 쓰는 임시 트리거 요청. F3 파이프라인 정식 연동 전 테스트용입니다. */
    public record ExposureTriggerRequest(
            String requestId,
            String eventId,
            String materialCategory,
            String impactDomain,
            double externalSignalScore,
            String externalSignalLevel,
            String affectedCountryCode,
            String eventSummary) {
    }
}

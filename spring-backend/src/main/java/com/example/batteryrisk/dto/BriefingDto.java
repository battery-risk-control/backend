package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 13단계 템플릿 브리핑의 외부 API, FastAPI 내부 통신, 저장 결과 DTO를 한 파일에 모읍니다. */
public final class BriefingDto {
    private BriefingDto() {}

    public record GenerateRequest(
            @NotBlank
            @Schema(example = "MAT-LI-CARB", description = "외부 ERP 자재 ID")
            String erpMaterialId,

            @Schema(example = "SUP-CHL-01", description = "영향 공급사 ERP ID. 없으면 우선순위 1 공급사를 사용")
            String erpSupplierId,

            @NotNull
            @Schema(example = "2026-07-24T12:00:00+09:00", description = "분석 기준 시각")
            OffsetDateTime asOf,

            @Schema(example = "11.5", description = "가격 변동률(%) 또는 null")
            BigDecimal priceChangeRate,

            @DecimalMin("0.0")
            @Schema(example = "7", description = "물류 지연 일수 또는 null")
            BigDecimal logisticsDelayDays,

            @Min(0) @Max(2)
            @Schema(example = "2", description = "GDACS 0/1/2 또는 null")
            Integer gdacsAlertLevel,

            @Size(max = 2000)
            @Schema(example = "가격 조정 공급 지연", description = "계약 근거 검색어. 없으면 자재명으로 검색")
            String query,

            @Min(1) @Max(20)
            @Schema(example = "5", description = "계약 근거 최대 건수. 기본 5")
            Integer topK
    ) {
        public int resolvedTopK() {
            return topK == null ? 5 : topK;
        }
    }

    /** FastAPI /api/v1/internal/briefings/compose 요청 본문입니다. */
    public record FastApiComposeRequest(
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("material_name") String materialName,
            String unit,
            String severity,
            BigDecimal score,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("inventory_days") BigDecimal inventoryDays,
            @JsonProperty("safety_stock_days") BigDecimal safetyStockDays,
            @JsonProperty("available_quantity") BigDecimal availableQuantity,
            @JsonProperty("expected_supply_gap_days") BigDecimal expectedSupplyGapDays,
            @JsonProperty("next_eta_days") Integer nextEtaDays,
            @JsonProperty("supplier_dependency_ratio") BigDecimal supplierDependencyRatio,
            @JsonProperty("primary_supplier_id") String primarySupplierId,
            @JsonProperty("primary_supplier_status") String primarySupplierStatus,
            @JsonProperty("feoc_status") String feocStatus,
            @JsonProperty("data_quality_status") String dataQualityStatus,
            @JsonProperty("contract_evidence") List<ContractEvidence> contractEvidence,
            @JsonProperty("alternative_suppliers") List<AlternativeSupplier> alternativeSuppliers,
            boolean mock
    ) {}

    public record ContractEvidence(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("page_number") Integer pageNumber,
            String content,
            @JsonProperty("similarity_score") Double similarityScore
    ) {}

    public record AlternativeSupplier(
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("supplier_name") String supplierName,
            @JsonProperty("approved_status") String approvedStatus,
            @JsonProperty("feoc_status") String feocStatus,
            @JsonProperty("iatf_16949_certified") boolean iatf16949Certified,
            @JsonProperty("ppap_approved") boolean ppapApproved,
            @JsonProperty("lead_time_days") Integer leadTimeDays
    ) {}

    public record FastApiComposeResponse(
            boolean success,
            FastApiComposeResult data,
            OffsetDateTime timestamp
    ) {}

    public record FastApiComposeResult(
            String headline,
            @JsonProperty("risk_summary") String riskSummary,
            @JsonProperty("inventory_summary") String inventorySummary,
            @JsonProperty("supplier_dependency_summary") String supplierDependencySummary,
            @JsonProperty("supply_gap_summary") String supplyGapSummary,
            @JsonProperty("contract_evidence_summary") String contractEvidenceSummary,
            @JsonProperty("alternative_supplier_summary") String alternativeSupplierSummary,
            @JsonProperty("recommended_checks") List<String> recommendedChecks,
            List<String> warnings,
            @JsonProperty("template_version") String templateVersion,
            boolean mock
    ) {}

    /** 목록 화면용 요약 항목입니다. 본문 전체 대신 headline과 핵심 지표만 담습니다. */
    public record BriefingListItem(
            UUID briefingId,
            String erpMaterialId,
            String erpSupplierId,
            String severity,
            BigDecimal score,
            String headline,
            int contractEvidenceCount,
            int alternativeSupplierCount,
            OffsetDateTime assessedAt,
            OffsetDateTime createdAt
    ) {}

    /** PostgreSQL에 저장하고 조회 API가 반환하는 브리핑 결과입니다. */
    public record BriefingResponse(
            UUID briefingId,
            @Schema(description = "F7 근거 계보: 이 브리핑이 근거로 삼은 Severity 판정 ID. 옛 브리핑은 null")
            UUID assessmentId,
            Long materialId,
            Long supplierId,
            String erpMaterialId,
            String erpSupplierId,
            OffsetDateTime assessedAt,
            String severity,
            BigDecimal score,
            String headline,
            String riskSummary,
            String inventorySummary,
            String supplierDependencySummary,
            String supplyGapSummary,
            String contractEvidenceSummary,
            String alternativeSupplierSummary,
            List<String> reasonCodes,
            List<String> recommendedChecks,
            List<String> warnings,
            List<ContractEvidence> contractEvidence,
            List<AlternativeSupplier> alternativeSuppliers,
            String templateVersion,
            String ruleVersion,
            boolean mock,
            OffsetDateTime createdAt
    ) {}

    /**
     * F7 근거 계보(Phase 2) 응답.
     *
     * <p>브리핑 결론에서 시작해 근거를 한 번에 되짚는다:
     * 결론(severity·score) → Severity 판정(ERP 입력 스냅샷·reason_codes·rule_version) → 계약 근거 청크 → 버전.
     * V8 이전에 만들어진 브리핑은 링크가 없어 {@code assessment}가 null이다.
     */
    public record LineageResponse(
            UUID briefingId,
            String erpMaterialId,
            String erpSupplierId,
            String severity,
            BigDecimal score,
            String headline,
            OffsetDateTime assessedAt,
            OffsetDateTime createdAt,
            List<String> reasonCodes,
            List<String> warnings,
            String templateVersion,
            String ruleVersion,
            boolean mock,
            @Schema(description = "근거가 된 Severity 판정과 그 ERP 입력 스냅샷. 옛 브리핑은 null")
            SeverityDto.AssessmentResponse assessment,
            @Schema(description = "근거가 된 계약 청크 목록")
            List<ContractEvidence> contractEvidence
    ) {}
}

package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class MultiAgentDto {
    private MultiAgentDto() {}

    public record GenerateRequest(
        String newsId,
        String title,
        String articleText,
        String summaryKr,
        String impactDomainDraft,
        String impactDomainFinal,
        String externalSignalLevel,
        int externalSignalScore,
        String erpMaterialId,
        String erpSupplierId,
        java.time.OffsetDateTime asOf,
        boolean useLlm
    ) {}
    /**
     * Spring → FastAPI 멀티에이전트 요청.
     *
     * erpContext에는 수정님 ERP Exposure Agent의
     * ErpExposureRequest 구조를 그대로 전달합니다.
     */
    public record Request(
            @JsonProperty("news_id")
            String newsId,

            String title,

            @JsonProperty("article_text")
            String articleText,

            @JsonProperty("summary_kr")
            String summaryKr,

            @JsonProperty("impact_domain_draft")
            String impactDomainDraft,

            @JsonProperty("impact_domain_final")
            String impactDomainFinal,

            @JsonProperty("affected_materials")
            List<String> affectedMaterials,

            @JsonProperty("external_signal_level")
            String externalSignalLevel,

            @JsonProperty("external_signal_score")
            int externalSignalScore,

            @JsonProperty("erp_context")
            Map<String, Object> erpContext,

            @JsonProperty("rag_contract_id")
            Long ragContractId,

            @JsonProperty("rag_supplier_id")
            Long ragSupplierId,

            @JsonProperty("rag_material_id")
            Long ragMaterialId,

            @JsonProperty("use_llm")
            boolean useLlm
    ) {}
    public record FastApiResponse(
        boolean success,
        Response data,
        java.time.OffsetDateTime timestamp
    ) {}
    /**
     * FastAPI → Spring 멀티에이전트 응답.
     *
     * Agent별 상세 결과는 구조 변경 가능성이 있으므로
     * 현재 통합 단계에서는 Map으로 받습니다.
     */
    public record Response(
        @JsonProperty("newsId")
        String newsId,

        @JsonProperty("impactDomainFinal")
        String impactDomainFinal,

        @JsonProperty("procurementRiskLevel")
        String procurementRiskLevel,

        @JsonProperty("procurementRiskScore")
        int procurementRiskScore,

        @JsonProperty("riskReasons")
        List<String> riskReasons,

        @JsonProperty("erpAssessment")
        Map<String, Object> erpAssessment,

        @JsonProperty("erpReassessment")
        Map<String, Object> erpReassessment,

        @JsonProperty("contractAssessment")
        Map<String, Object> contractAssessment,

        @JsonProperty("contractFindings")
        List<Map<String, Object>> contractFindings,

        @JsonProperty("recommendedActions")
        List<String> recommendedActions,

        String briefing,

        @JsonProperty("llmUsed")
        boolean llmUsed,

        @JsonProperty("llmError")
        String llmError,

        @JsonProperty("reviewPassed")
        boolean reviewPassed,

        List<String> warnings
) {}
}
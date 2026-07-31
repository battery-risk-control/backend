package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class MultiAgentDto {
    private MultiAgentDto() {}

    /**
     * 클라이언트 → Spring 멀티에이전트 브리핑 요청.
     *
     * <p>외부신호(가중치 0.35)는 두 경로로 채울 수 있다.
     * <ul>
     *   <li>{@code analysisId} 지정 — Spring이 {@code analyses}에서 {@code severity_score}·
     *       {@code severity}를 읽어 채운다. <b>권장 경로</b>다. F3 분석 결과가 곧 외부신호 점수이므로
     *       (severity_engine v0.2-realtime) 호출자가 값을 따로 계산할 필요가 없다.</li>
     *   <li>{@code externalSignalScore}·{@code externalSignalLevel} 직접 지정 — 기존 계약.
     *       분석을 거치지 않은 임의 이벤트를 넣어볼 때 쓴다.</li>
     * </ul>
     *
     * <p>둘 다 비면 400이다. 예전처럼 조용히 0점으로 넘어가지 않는다 — 0점이 들어가면
     * FastAPI {@code risk_node}의 가중치 0.35 항목이 통째로 죽어 종합 점수가 최대 35점 낮게 나온다.
     */
    public record GenerateRequest(
        @JsonProperty("news_id")
        @JsonAlias("newsId")
        @NotBlank
        String newsId,

        /** 외부신호를 끌어올 {@code analyses} 행. 지정하면 external_signal_* 는 무시된다. */
        @JsonProperty("analysis_id")
        @JsonAlias("analysisId")
        java.util.UUID analysisId,

        @NotBlank
        String title,

        @JsonProperty("article_text")
        @JsonAlias("articleText")
        String articleText,

        @JsonProperty("summary_kr")
        @JsonAlias("summaryKr")
        String summaryKr,

        @JsonProperty("impact_domain_draft")
        @JsonAlias("impactDomainDraft")
        String impactDomainDraft,

        @JsonProperty("impact_domain_final")
        @JsonAlias("impactDomainFinal")
        @NotBlank
        String impactDomainFinal,

        // analysisId로 채울 수 있게 되면서 필수 제약을 뗐다. 둘 다 비면
        // MultiAgentOrchestrationService가 EXTERNAL_SIGNAL_REQUIRED로 막는다.
        @JsonProperty("external_signal_level")
        @JsonAlias("externalSignalLevel")
        String externalSignalLevel,

        // int였으나 "값 없음"과 "0점"을 구분해야 해서 Integer로 바꿨다.
        @JsonProperty("external_signal_score")
        @JsonAlias("externalSignalScore")
        @Min(0)
        @Max(100)
        Integer externalSignalScore,

        @JsonProperty("erp_material_id")
        @JsonAlias("erpMaterialId")
        @NotBlank
        String erpMaterialId,

        @JsonProperty("erp_supplier_id")
        @JsonAlias("erpSupplierId")
        @NotBlank
        String erpSupplierId,

        // KG 게이트(analyze_kg_context_node, FastAPI 쪽)가 country+affected_materials 없이는
        // 항상 "매칭 없음"으로 조기종료해버려서(2026-08-01 Docker 실증 중 발견 — 이 필드가
        // 없어서 Chain B가 실제로는 한 번도 본 파이프라인을 안 타고 있었음), 반드시 실어 보내야 한다.
        @JsonProperty("country")
        String country,

        @JsonProperty("as_of")
        @JsonAlias("asOf")
        @NotNull
        java.time.OffsetDateTime asOf,

        @JsonProperty("use_llm")
        @JsonAlias("useLlm")
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

            @JsonProperty("country")
            String country,

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
        /**
         * 저장된 {@code procurement_risk_assessments} 행의 id.
         *
         * <p>FastAPI는 이 값을 모른다 — Spring이 저장한 뒤 {@link #withAssessmentId(java.util.UUID)}로
         * 채워 넣는다. 그래서 FastAPI 응답을 역직렬화한 직후에는 항상 null이다.
         */
        @JsonProperty("assessmentId")
        java.util.UUID assessmentId,

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
) {
        /** 저장 후 assessmentId만 채운 복사본. 나머지 필드는 FastAPI 응답 그대로다. */
        public Response withAssessmentId(java.util.UUID assessmentId) {
            return new Response(
                    assessmentId, newsId, impactDomainFinal, procurementRiskLevel,
                    procurementRiskScore, riskReasons, erpAssessment, erpReassessment,
                    contractAssessment, contractFindings, recommendedActions, briefing,
                    llmUsed, llmError, reviewPassed, warnings);
        }
}
}
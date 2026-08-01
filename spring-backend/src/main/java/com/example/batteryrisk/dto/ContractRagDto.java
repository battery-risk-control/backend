package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1계층 구매팀 "계약 · RAG 검색" 화면 전용 DTO.
 *
 * <p>기존 {@link RagDto}(멀티에이전트가 쓰는 검색 계약)와 이름이 겹치지 않게 따로 둔다 —
 * 저쪽은 필터가 필수이고 청크를 그대로 돌려주는 반면, 이 화면은 필터 없이 전체 계약을 훑고
 * 조항 제목·계약 메타까지 붙여서 내려줘야 한다.
 */
public final class ContractRagDto {
    private ContractRagDto() {}

    // ---------------------------------------------------------------- 계약 목록·상세

    /** 계약 선택 드롭다운과 검색 결과 카드에 함께 붙는 계약 요약. */
    public record ContractSummary(
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("erp_contract_id") String erpContractId,
            @JsonProperty("contract_name") String contractName,
            String status,
            @JsonProperty("start_date") LocalDate startDate,
            @JsonProperty("end_date") LocalDate endDate,
            @JsonProperty("currency_code") String currencyCode,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("supplier_name") String supplierName,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("material_id") Long materialId,
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("material_name") String materialName,
            @JsonProperty("material_category") String materialCategory,
            @JsonProperty("document_count") int documentCount,
            @JsonProperty("indexed_chunk_count") int indexedChunkCount
    ) {}

    /** 우측 "계약 문서" 패널. 계약 메타 + 적재된 원본 문서와 임베딩 상태. */
    public record ContractDetail(
            ContractSummary contract,
            List<DocumentItem> documents,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("mock_embedding") Boolean mockEmbedding,
            /** 이 계약으로 AI 브리핑을 돌릴 수 있는지. 화면이 버튼을 미리 비활성화한다. */
            @JsonProperty("briefing_available") boolean briefingAvailable,
            @JsonProperty("briefing_blocked_reason") String briefingBlockedReason
    ) {}

    public record DocumentItem(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("original_file_name") String originalFileName,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("file_size_bytes") long fileSizeBytes,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("processed_at") Instant processedAt
    ) {}

    // ---------------------------------------------------------------- 조항 검색

    /**
     * 조항 검색 요청. {@code contractId}를 비우면 <b>전체 계약</b>을 훑는다 — 화면은 검색창에
     * 단어만 넣는 흐름이라 이게 기본이고, 계약을 고르면 그 계약으로 좁힌다.
     */
    public record SearchRequest(
            @NotBlank @Size(max = 2000) String query,
            @JsonProperty("contract_id") @Positive Long contractId,
            @JsonProperty("supplier_id") @Positive Long supplierId,
            @JsonProperty("material_id") @Positive Long materialId,
            @JsonProperty("top_k") @Min(1) @Max(50) Integer topK
    ) {
        public int resolvedTopK() {
            return topK == null ? 5 : topK;
        }
    }

    public record SearchResponse(
            String query,
            /** "all"이면 전체 계약, "filtered"면 특정 계약으로 좁힌 검색이다. */
            String scope,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("result_count") int resultCount,
            /** true면 mock 임베딩이라 유사도 점수에 의미가 없다. 화면이 경고를 띄운다. */
            boolean mock,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            List<SearchItem> results
    ) {}

    /** 조항 카드 한 장. */
    public record SearchItem(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("chunk_index") int chunkIndex,
            @JsonProperty("page_number") int pageNumber,
            /** "제4조 · 납기 및 지연 위약금" 형태의 표시용 제목. 청크 본문 머리에서 뽑는다. */
            @JsonProperty("clause_title") String clauseTitle,
            /** "제4조" / "Article 4". 조항 번호를 못 찾으면 null. */
            @JsonProperty("clause_no") String clauseNo,
            /** 원문 조항 표제("DELIVERY AND PENALTY"). 한글 라벨 매핑 전 값이다. */
            @JsonProperty("clause_heading") String clauseHeading,
            @JsonProperty("similarity_score") double similarityScore,
            String content,
            @JsonProperty("content_hash") String contentHash,
            /** 검색 출처. 현재는 항상 "chroma"다. */
            String source,
            ContractSummary contract
    ) {}

    // ---------------------------------------------------------------- 업로드·재처리

    public record UploadResponse(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("original_file_name") String originalFileName,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            /** 같은 계약에 같은 내용이 이미 있어 재적재하지 않았다는 뜻. */
            boolean duplicate,
            boolean mock,
            @JsonProperty("processed_at") Instant processedAt
    ) {}

    /** "문서 재처리" 결과. 계약에 달린 문서를 전부 다시 임베딩한다. */
    public record ReprocessResponse(
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("success_count") int successCount,
            @JsonProperty("failed_count") int failedCount,
            List<ReprocessItem> documents
    ) {}

    public record ReprocessItem(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("original_file_name") String originalFileName,
            boolean success,
            @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage
    ) {}

    // ---------------------------------------------------------------- AI 브리핑

    /**
     * "이 근거로 AI 브리핑 생성" 요청.
     *
     * <p>{@code evidence}는 화면에서 "근거로 사용하기"로 고른 조항들이다. 현재 멀티에이전트
     * 그래프에는 외부 근거를 주입하는 입구가 없어서(병합 진행 중) 검색어 보강과 응답 echo에만
     * 쓴다 — 그래프가 준비되면 그때 실제 주입으로 바꾼다.
     */
    public record BriefingRequest(
            @JsonProperty("contract_id") @Positive Long contractId,
            List<EvidenceRef> evidence,
            @JsonProperty("use_llm") boolean useLlm
    ) {}

    public record EvidenceRef(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("chunk_index") Integer chunkIndex,
            @JsonProperty("clause_title") String clauseTitle
    ) {}

    public record BriefingResponse(
            @JsonProperty("assessment_id") UUID assessmentId,
            ContractSummary contract,
            /** 브리핑의 입력이 된, DB에 저장돼 있던 가장 최신 관련 뉴스. */
            @JsonProperty("source_news") SourceNews sourceNews,
            /**
             * ERP·계약 노드까지 실제로 돌아 종합 점수가 나온 실행인지.
             *
             * <p>false면 KG 게이트에서 조기 종료된 실행이라 등급이 항상 "정상 · 0점"으로 남는다 —
             * "평가해보니 정상"이 아니라 <b>"평가하지 못했다"</b>는 뜻이므로 화면은 이 값을 보고
             * 점수를 그대로 보여주지 말아야 한다({@code RiskMonitoringService.isComposite}와 같은 규칙).
             */
            boolean composite,
            @JsonProperty("procurement_risk_level") String procurementRiskLevel,
            @JsonProperty("procurement_risk_score") int procurementRiskScore,
            @JsonProperty("risk_reasons") List<String> riskReasons,
            String briefing,
            @JsonProperty("recommended_actions") List<String> recommendedActions,
            @JsonProperty("contract_findings") List<Map<String, Object>> contractFindings,
            @JsonProperty("used_evidence") List<EvidenceRef> usedEvidence,
            @JsonProperty("llm_used") boolean llmUsed,
            @JsonProperty("review_passed") boolean reviewPassed,
            List<String> warnings
    ) {}

    /** 브리핑의 근거가 된 뉴스. 화면이 "무엇을 보고 이렇게 판단했는지" 되짚을 수 있게 한다. */
    public record SourceNews(
            @JsonProperty("analysis_id") UUID analysisId,
            @JsonProperty("event_id") Long eventId,
            String title,
            @JsonProperty("title_ko") String titleKo,
            @JsonProperty("summary_kr") String summaryKr,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("material_category") String materialCategory,
            @JsonProperty("impact_domain") String impactDomain,
            String severity,
            @JsonProperty("severity_score") Integer severityScore,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("collected_at") Instant collectedAt,
            @JsonProperty("completed_at") Instant completedAt
    ) {}
}

package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1계층 구매팀 "원자재 위험" 화면(상단 KPI + 자재 목록 + 자재 상세)용 조회 응답 DTO.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE라 카멜케이스 필드는 {@code erp_material_id},
 * {@code supplier_dependency_ratio} 등으로 직렬화된다.
 *
 * <p><b>{@link RiskMonitoringDto}와의 차이</b>: 저쪽은 <i>수집 뉴스</i>가 원천이라 "밖에서 무슨 일이
 * 있었나"를 보여준다. 이쪽은 <i>ERP 테이블</i>이 원천이라 뉴스가 한 건도 없어도 "우리 재고·발주·
 * 공급사 구조가 지금 얼마나 위태로운가"를 보여준다. 그래서 목록의 축이 기사(raw_events)가 아니라
 * 자재(materials)다.
 *
 * <p>점수와 등급은 Spring이 자체 계산하지 않는다 — 멀티에이전트의 ERP Exposure Agent
 * ({@code POST /api/v1/internal/erp/exposure}, 규칙은 {@code fastapi-ai/app/config/erp_rules.yaml})가
 * 돌려준 값을 그대로 옮긴다. 화면과 멀티에이전트가 같은 자재를 다른 점수로 부르지 않게 하기 위함이다.
 */
public final class MaterialRiskDto {
    private MaterialRiskDto() {}

    /**
     * 화면 1회 로드분 — 상단 KPI와 좌측 목록을 한 번에 내려준다.
     *
     * <p>둘을 나누지 않은 이유: KPI(평가 자재·심각 건수·평균 재고일수·데이터 품질)가 목록과
     * <b>완전히 같은 계산 결과</b>에서 나온다. 엔드포인트를 나누면 자재 하나당 ERP Exposure Agent
     * 호출이 두 배가 된다.
     */
    public record Overview(Summary summary, List<MaterialItem> materials) {}

    /** 상단 KPI 4장. */
    public record Summary(
            @Schema(example = "11", description = "점수가 실제로 산출된 자재 수. 데이터 부족으로 "
                    + "평가하지 못한 자재는 여기에 세지 않고 unavailable_count로 뺀다.")
            long assessedMaterialCount,

            @Schema(example = "3", description = "심각(CRITICAL) 자재 수")
            long criticalCount,

            @Schema(example = "2", description = "주의(WARNING) 자재 수")
            long warningCount,

            @Schema(example = "6", description = "정상(NORMAL) 자재 수")
            long normalCount,

            @Schema(example = "1", description = "재고·발주 데이터가 없어 평가하지 못한 자재 수")
            long unavailableCount,

            @Schema(example = "15.8", description = "평가된 자재의 재고일수 평균. 소수 첫째 자리")
            BigDecimal averageInventoryDays,

            @Schema(example = "VALID", description = "위 KPI 숫자를 만든 데이터의 품질. "
                    + "점수가 나온 자재(assessed_material_count)의 품질 중 가장 나쁜 값이다 "
                    + "(VALID < STALE < INCOMPLETE < INVALID). 평가하지 못한 자재는 이 값에 넣지 않는다 "
                    + "— 그쪽은 늘 더 나쁜 값이라 섞으면 평가된 자재의 상태가 가려진다. "
                    + "평가 못한 자재는 unavailable_count와 자재별 unavailable_reason으로 본다.")
            String dataQualityStatus,

            @Schema(description = "이 집계의 ERP 기준 시각(계산 시점 now, 재고 신선도 판정에 쓰인다)")
            OffsetDateTime asOf,

            @Schema(description = "헤더 '기준' 칩용 — 최신 공급망 뉴스 수집 시각(MAX collected_at). "
                    + "2·3계층 대시보드 as_of와 같은 소스라 네 화면의 기준 시각이 일치한다. 수집 뉴스가 없으면 null.")
            OffsetDateTime newsAsOf
    ) {}

    /**
     * 목록 1행.
     *
     * <p>{@code grade}(심각/주의/정상)와 {@code exposureLevel}(CRITICAL/WARNING/NORMAL)은 같은 것을
     * 다르게 표기한 값이다. 화면은 grade를, 로직·필터는 exposureLevel을 쓰라는 뜻이다 —
     * 한국어 표기를 조건문에 쓰면 표기가 바뀔 때 로직이 조용히 깨진다.
     */
    public record MaterialItem(
            @Schema(example = "MAT-CO-SULF", description = "외부 ERP 자재 ID. 상세·RAG·브리핑 호출의 경로 변수")
            String erpMaterialId,

            @Schema(example = "Cobalt Sulfate") String materialName,

            @Schema(example = "COBALT", description = "자재 대분류. 브리핑의 외부신호를 고를 때 이 값으로 뉴스를 찾는다.")
            String materialCategory,

            @Schema(example = "심각", description = "심각/주의/정상. 평가하지 못한 자재는 null")
            String grade,

            @Schema(example = "CRITICAL", description = "CRITICAL/WARNING/NORMAL/UNKNOWN")
            String exposureLevel,

            @Schema(example = "70", description = "ERP 노출도 점수(0~100). 평가하지 못한 자재는 null")
            BigDecimal score,

            @Schema(example = "6.5", description = "현재 가용재고로 버틸 수 있는 일수")
            BigDecimal inventoryDays,

            @Schema(example = "18", description = "안전재고를 일수로 환산한 값")
            BigDecimal safetyStockDays,

            @Schema(example = "0.84", description = "주 공급사 의존도(0~1). 화면에서 %로 환산한다.")
            BigDecimal supplierDependencyRatio,

            @Schema(example = "VALID", description = "VALID/STALE/INCOMPLETE/INVALID/UNKNOWN")
            String dataQualityStatus,

            @Schema(example = "분석 기준 시각에 사용할 수 있는 현재 재고가 없습니다.",
                    description = "평가하지 못한 이유. 평가에 성공했으면 null")
            String unavailableReason
    ) {}

    /** 우측 상세 패널 — 목록 1행에 ERP 노출 정보·주 공급사·연결 계약·세부 점수를 더한 것. */
    public record MaterialDetail(
            String erpMaterialId,
            String materialName,
            String materialCategory,
            String grade,
            String exposureLevel,
            BigDecimal score,
            BigDecimal inventoryDays,
            BigDecimal safetyStockDays,
            BigDecimal supplierDependencyRatio,
            String dataQualityStatus,
            String unavailableReason,

            @Schema(example = "KG") String unit,
            BigDecimal onHandQuantity,
            BigDecimal availableQuantity,
            BigDecimal safetyStockQuantity,
            BigDecimal averageDailyUsage,

            @Schema(description = "가장 이른 미입고 발주의 도착 예정일. 미입고 발주가 없으면 null")
            LocalDate nextInboundDate,

            @Schema(example = "9", description = "오늘부터 다음 입고까지 남은 일수")
            Integer nextEtaDays,

            @Schema(example = "2.5", description = "재고 소진 후 다음 입고까지의 공백일수. max(0, ETA - 재고일수)")
            BigDecimal expectedSupplyGapDays,

            @Schema(description = "계산에 쓰인 재고 스냅샷 시각. 이 숫자들이 언제 기준인지 화면이 스스로 "
                    + "판단할 수 있도록 그대로 노출한다.")
            Instant inventorySnapshotAt,

            PrimarySupplier primarySupplier,

            @Schema(description = "주 공급사와의 공급관계에 걸린 계약. RAG가 아니라 ERP "
                    + "(supplier_materials → contracts)에서 온다.")
            LinkedContract linkedContract,

            @Schema(description = "ERP 노출도 점수를 구성한 5개 세부 점수. 왜 이 등급인지 화면에서 풀어 보여줄 때 쓴다.")
            RiskComponents riskComponents,

            @Schema(description = "점수와 무관하게 CRITICAL로 격상된 건인지 (예: 다음 입고 전 재고 소진)")
            boolean forcedCritical,

            @Schema(description = "ERP Agent가 계약 검토가 필요하다고 판단했는지. 화면의 '계약 RAG 근거 보기' 강조에 쓴다.")
            boolean contractReviewRequired,

            @Schema(description = "ERP Agent가 계약 Agent에 던지려고 만든 질문. RAG 검색의 질의로 그대로 쓴다.")
            List<ContractQuestion> contractQuestions,

            @Schema(description = "ERP Agent가 남긴 경고(재고 스냅샷 노후 등)")
            List<String> warnings,

            @Schema(description = "'AI 브리핑 생성' 버튼을 누를 수 있는지. false면 이유가 blocked_reason에 담긴다.")
            boolean briefingAvailable,

            @Schema(example = "COBALT 대분류의 저장된 뉴스 분석이 없어 외부신호를 만들 수 없습니다.",
                    description = "브리핑 생성 불가 사유. 가능하면 null")
            String briefingBlockedReason,

            OffsetDateTime asOf
    ) {}

    /** 주 공급사 — 공급 비중 1순위(priority_rank) 공급사. */
    public record PrimarySupplier(
            @Schema(example = "SUP-COD-01") String erpSupplierId,
            @Schema(example = "Kolwezi Cobalt Refinery") String supplierName,
            @Schema(example = "ACTIVE", description = "ACTIVE/UNDER_REVIEW/SUSPENDED/INACTIVE")
            String supplierStatus,

            @Schema(example = "CONDITIONAL", description = "대체 공급사 확보 상태. "
                    + "APPROVED/CONDITIONAL/PENDING, 쓸 수 있는 대체가 없으면 NONE")
            String alternativeSupplierStatus,

            @Schema(example = "NO", description = "해외우려기관(FEOC) 해당 여부. YES/NO/UNKNOWN")
            String feocStatus
    ) {}

    /** 연결 계약 — 목업의 "CTR-010 · contract_id 11". */
    public record LinkedContract(
            @Schema(example = "11", description = "내부 PK. RAG 검색 필터로 그대로 쓴다.")
            Long contractId,
            @Schema(example = "CTR-010") String erpContractId,
            String contractNumber,
            String contractName,
            @Schema(example = "ACTIVE", description = "DRAFT/ACTIVE/EXPIRED/TERMINATED")
            String status,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    /**
     * ERP 노출도 점수의 세부 항목. 가중치는 {@code erp_rules.yaml}에 있다
     * (공급공백 .35 / 안전재고 .20 / 의존도 .20 / 발주지연 .15 / 대체공급사 .10).
     */
    public record RiskComponents(
            BigDecimal gapRiskScore,
            BigDecimal safetyStockRiskScore,
            BigDecimal dependencyRiskScore,
            BigDecimal purchaseOrderDelayRiskScore,
            BigDecimal alternativeSupplierRiskScore
    ) {}

    /** ERP Agent가 계약 Agent에 던지는 질문 1건. */
    public record ContractQuestion(
            @Schema(example = "DELIVERY_DELAY_NOTICE") String questionCode,
            @Schema(example = "계약서에 납기 지연 발생 시 공급사의 통보 의무가 있는가?") String question
    ) {}

    /** "계약 RAG 근거 보기" 응답 — 무엇을 물었고(questions) 어떤 조항이 걸렸는지(results). */
    public record ContractEvidence(
            String erpMaterialId,
            LinkedContract linkedContract,

            @Schema(description = "검색에 사용한 질문. ERP Agent가 만든 것을 그대로 쓰고, 없으면 기본 질의 1건")
            List<ContractQuestion> questions,

            @Schema(description = "실제로 FastAPI ChromaDB에 넘어간 질의 문자열")
            String query,

            List<ClauseHit> results,

            @Schema(description = "임베딩이 mock인지. true면 유사도 점수를 신뢰하면 안 된다.")
            boolean mock
    ) {}

    /**
     * 검색에 걸린 계약 조항 1건.
     *
     * <p>{@link RagDto.SearchItem}(FastAPI 응답 그대로)에 <b>조항 제목 두 개를 더한</b> 것이다.
     * 나머지 필드는 이름·타입이 그대로라 기존 소비자에게는 필드가 늘기만 한다.
     *
     * <p>제목을 붙이는 이유: 청크 하나가 {@code 4.01·4.02·4.03}을 통째로 담고 있어서 원문만
     * 보여주면 "납기 지연 위약금"을 물었는데 어느 조항이 답인지 화면에서 알 수 없다. 제목은
     * ChromaDB에 없는 값이라 청크 본문 머리에서 뽑는다 —
     * {@link com.example.batteryrisk.service.ContractRagService.ClauseHeading}을 그대로 재사용하므로
     * 계약·RAG 화면과 같은 문구가 나온다(LLM 호출 없음).
     */
    public record ClauseHit(
            String documentId,
            Long contractId,
            Long supplierId,
            Long materialId,
            String documentType,
            int chunkIndex,
            int pageNumber,

            @Schema(example = "제4조", description = "조항 번호. 조항이 아닌 청크(표지·서문)면 null")
            String clauseNo,

            @Schema(example = "제4조 · 납기 및 지연 위약금",
                    description = "화면에 띄울 조항 제목. 영문 표제는 한글 라벨로 바꾸고, 매핑에 없으면 "
                            + "원문을 그대로 쓴다. 조항 머리를 못 찾으면 본문 첫 줄을 잘라 쓴다.")
            String clauseTitle,

            String content,
            double similarityScore,
            boolean mockEmbedding
    ) {}

}

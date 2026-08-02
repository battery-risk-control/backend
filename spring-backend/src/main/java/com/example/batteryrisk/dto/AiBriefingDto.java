package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1계층 구매팀 "AI 브리핑" 화면 DTO.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE라 카멜케이스 필드는 {@code erp_material_id},
 * {@code procurement_risk_level} 등으로 직렬화된다(다른 1계층 화면 DTO와 같다).
 *
 * <p>이 화면은 값을 <b>새로 계산하지 않는다.</b> 리스크 모니터링·원자재 위험·계약·RAG 세 화면에서
 * 넘어온 대상을 받아 멀티에이전트를 한 번 돌리고, 그 응답을 화면 칸에 맞춰 펼쳐 놓은 것이 전부다.
 * 그래서 여기 필드는 대부분 {@code MultiAgentDto.Response}의 재배치다 — 화면이 응답 Map을
 * 파싱하지 않아도 되도록 Spring이 미리 꺼내 준다.
 */
public final class AiBriefingDto {
    private AiBriefingDto() {}

    // ---------------------------------------------------------------- 프리필(분석 대상 · ERP 연결)

    /**
     * 화면 상단 프리필. 앞 화면에서 넘어온 대상이 무엇이고 어떤 ERP에 걸리는지만 보여주며,
     * <b>멀티에이전트를 돌리지 않는다</b> — 그래프 실행은 "LLM 브리핑 생성"을 눌렀을 때만이다.
     *
     * <p>{@code generateAvailable}은 앞 화면들의 {@code erp_impact_available}·
     * {@code briefing_available}과 <b>같은 판정</b>이다. 화면이 버튼을 미리 비활성화할 수 있게
     * 사유까지 함께 내려준다.
     */
    public record Context(
            @Schema(example = "NEWS", description = "NEWS/MATERIAL/CONTRACT. 어느 화면에서 넘어왔는지")
            String sourceType,

            @Schema(example = "252", description = "그 화면이 넘긴 식별자(eventId · erp_material_id · contract_id)")
            String sourceRef,

            @Schema(example = "코발트 공급사의 납기 지연", description = "화면 상단 '분석 대상'")
            String subjectTitle,

            @Schema(example = "news-integration-001", description = "분석 대상 아래 식별자. 멀티에이전트에 넘기는 news_id와 같다.")
            String newsId,

            @Schema(description = "외부신호(가중치 0.35)로 쓸 analyses 행")
            UUID analysisId,

            @Schema(description = "외부신호로 쓰는 기사 제목. 자재·계약에서 넘어온 경우 subject_title과 다르다.")
            String sourceHeadline,

            @Schema(example = "MAT-CO-SULF", description = "ERP 연결 ①") String erpMaterialId,
            @Schema(example = "SUP-COD-01", description = "ERP 연결 ②") String erpSupplierId,
            @Schema(example = "CTR-010", description = "ERP 연결 ③") String erpContractId,

            @Schema(example = "11", description = "내부 계약 PK. 계약 RAG 검색 필터로 쓰는 값이다.")
            Long contractId,

            String materialName,
            @Schema(example = "COBALT") String materialCategory,
            @Schema(example = "CD") String countryCode,
            @Schema(example = "PRODUCTION") String impactDomain,

            @Schema(example = "WARNING") String externalSignalLevel,
            @Schema(example = "60.0", description = "외부신호 점수(0~100)") BigDecimal externalSignalScore,

            @Schema(description = "'LLM 브리핑 생성'을 지금 누를 수 있는지. false면 사유가 blocked_reason에 담긴다.")
            boolean generateAvailable,

            @Schema(example = "공급망과 무관한 뉴스로 판정되었습니다.", description = "생성 불가 사유. 가능하면 null")
            String generateBlockedReason
    ) {}

    /** "LLM 브리핑 생성" 요청. 프리필을 받은 그 대상을 그대로 다시 보낸다. */
    public record GenerateRequest(
            @NotBlank
            @Schema(example = "NEWS", description = "NEWS/MATERIAL/CONTRACT")
            String source,

            @NotBlank
            @Schema(example = "252", description = "eventId · erp_material_id · contract_id")
            String ref,

            @Schema(description = "LLM으로 브리핑 문구를 생성할지. 버튼 이름이 'LLM 브리핑 생성'이라 "
                    + "생략하면 true다 — 다른 화면들(기본 false)과 반대인 점에 주의.")
            Boolean useLlm,

            /**
             * 프리필({@code GET /context})이 돌려준 외부신호 분석을 <b>고정</b>한다.
             *
             * <p>보내지 않으면 서버가 그 자리에서 다시 고르는데, 자재·계약 경로는 "같은 대분류의
             * 가장 최신 분석"을 고르므로 <b>프리필과 생성 사이에 수집 스케줄러가 새 분석을 넣으면
             * 다른 뉴스로 실행된다.</b> 화면이 "외부신호 60.9"를 보여주고 실제로는 다른 기사로 돌면
             * 상단과 결과가 어긋난다. 화면은 항상 프리필의 analysis_id를 그대로 실어 보낸다.
             *
             * <p>대상과 무관한 분석을 지정하면 400이다 — 고정이 곧 우회로가 되면 안 된다.
             */
            @Schema(description = "프리필이 돌려준 analysis_id. 생략하면 서버가 다시 고른다.")
            UUID analysisId
    ) {}

    // ---------------------------------------------------------------- 저장된 브리핑

    /** 우측 하단 "최근 브리핑" 카드 1장. 본문 없이 등급·검증 결과·시각만 담는다. */
    public record BriefingListItem(
            UUID briefingId,
            @Schema(example = "NEWS") String sourceType,

            @Schema(example = "252", description = "이 브리핑이 어떤 대상으로 생성됐는지. 화면이 "
                    + "상세를 열 때 상단 '분석 대상'까지 함께 맞추려면 source_type과 이 값이 필요하다.")
            String sourceRef,

            String subjectTitle,
            String newsId,
            @Schema(example = "CRITICAL") String procurementRiskLevel,
            @Schema(example = "70.0") BigDecimal procurementRiskScore,

            @Schema(description = "ERP·계약 노드까지 실제로 돈 실행인지. false면 점수를 등급으로 읽으면 안 된다.")
            boolean composite,

            @Schema(description = "reviewer 노드 검증 통과 여부. 화면의 '검증 통과' 표기")
            Boolean reviewPassed,

            OffsetDateTime createdAt
    ) {}

    /**
     * 브리핑 상세 — 사진의 좌측 "구매 위험 브리핑"과 우측 "분석 근거" 전체.
     *
     * <p>"LLM 브리핑 생성" 응답과 "브리핑 상세 보기" 응답이 <b>같은 타입</b>이다. 방금 만든 것과
     * 저장된 것을 화면이 다르게 그릴 이유가 없고, 두 모양이 갈라지면 같은 화면에서 칸이 어긋난다.
     */
    public record BriefingDetail(
            UUID briefingId,

            @Schema(description = "이 브리핑을 만든 실행이 procurement_risk_assessments에 남긴 행. 세부 점수 재조회용")
            UUID assessmentId,

            String sourceType,
            String sourceRef,
            String subjectTitle,
            String newsId,
            UUID analysisId,
            String sourceHeadline,

            String erpMaterialId,
            String erpSupplierId,
            String erpContractId,
            Long contractId,
            String materialName,
            @Schema(example = "COBALT") String materialCategory,
            @Schema(example = "PRODUCTION", description = "멀티에이전트에 넘긴 영향 도메인")
            String impactDomain,

            @Schema(description = "ERP·계약 노드까지 실제로 돌아 종합 점수가 나왔는지. "
                    + "false면 KG 게이트 조기 종료라 점수가 0 · NORMAL로 남으며 등급으로 읽으면 안 된다.")
            boolean composite,

            @Schema(example = "CRITICAL") String procurementRiskLevel,
            @Schema(example = "70.0") BigDecimal procurementRiskScore,

            @Schema(description = "브리핑 본문. use_llm=false거나 LLM 호출이 실패하면 규칙 기반 문구가 온다.")
            String briefing,

            @Schema(description = "판단 근거 문장. 조기 종료된 실행에서는 그 사유가 담긴다.")
            List<String> riskReasons,

            @Schema(description = "화면의 '구매팀 권고 조치'")
            List<String> recommendedActions,

            @Schema(description = "화면의 'ERP 노출 근거' 한 줄을 구성하는 값들")
            ErpEvidence erpEvidence,

            @Schema(description = "화면의 '계약서에서 확인된 근거'. contract_id·page·evidence_text가 들어 있다.")
            List<Map<String, Object>> contractFindings,

            @Schema(description = "우측 '분석 근거' 4칸")
            EvidenceChain evidenceChain,

            @Schema(description = "화면 하단 '검증 메타데이터'")
            VerificationMeta verification,

            OffsetDateTime createdAt
    ) {}

    /**
     * "ERP 노출 근거" — 사진의 "ERP 노출도 75 · 현재 재고 6.5일 · 안전재고 18일 / 예상 입고 9일 후 ·
     * 공급 공백 2.5일 · 공급사 의존도 84%".
     *
     * <p>앞 6개는 멀티에이전트의 {@code erp_assessment}에서 오고, {@code supplierDependencyRatio}만
     * ERP Context에서 온다 — ERP Exposure Agent가 의존도를 응답에 되돌려 주지 않기 때문이다.
     */
    public record ErpEvidence(
            @Schema(example = "75.0") BigDecimal exposureScore,
            @Schema(example = "CRITICAL", description = "NORMAL/WARNING/CRITICAL") String exposureLevel,
            @Schema(example = "6.5", description = "현재 재고로 버틸 수 있는 일수") BigDecimal inventoryDays,
            @Schema(example = "18.0") BigDecimal safetyStockDays,
            @Schema(example = "9", description = "다음 입고까지 남은 일수") Integer nextInboundEtaDays,
            @Schema(example = "2.5", description = "재고 소진 후 입고까지의 공백일수") BigDecimal expectedSupplyGapDays,
            @Schema(example = "0.84", description = "주 공급사 의존도(0~1). 화면은 %로 환산해 보여준다.")
            BigDecimal supplierDependencyRatio,

            @Schema(description = "다음 입고 전에 재고가 소진되는지. true면 가중합과 무관하게 심각으로 격상된다.")
            boolean stockoutBeforeEta
    ) {}

    /**
     * 우측 "분석 근거" — 외부 이벤트 → ERP 노출 → 계약 RAG → 최종 위험.
     *
     * <p>네 칸이 곧 멀티에이전트의 실행 순서이자 가중치 구성이다(외부 0.35 + ERP 0.45 + 계약공백 0.20).
     */
    public record EvidenceChain(Step externalSignal, Step erpExposure, Step contractRag, Step finalRisk) {}

    /** 분석 근거 한 칸. 등급·점수가 없는 칸(계약 RAG)은 note에 "3개 조항"처럼 요약이 들어간다. */
    public record Step(
            @Schema(example = "외부 이벤트") String label,
            @Schema(example = "WARNING") String level,
            @Schema(example = "60.0") BigDecimal score,
            @Schema(example = "3개 조항") String note
    ) {}

    /** "검증 메타데이터" 칸. reviewer 노드 결과와 재현에 필요한 버전 정보. */
    public record VerificationMeta(
            Boolean reviewPassed,
            boolean llmUsed,

            @Schema(description = "LLM 호출이 실패한 경우의 사유. 성공했거나 쓰지 않았으면 null")
            String llmError,

            int warningCount,
            List<String> warnings,

            @Schema(example = "11", description = "근거로 쓰인 첫 계약의 내부 PK") Long contractId,
            @Schema(example = "1", description = "근거로 쓰인 첫 조항의 페이지") Integer contractPage,

            @Schema(example = "procurement-risk-v1", description = "적용된 가중치 조합 버전")
            String weightVersion,

            @Schema(description = "외부신호의 출처 분석이 mock 데이터였는지")
            boolean mock
    ) {}
}

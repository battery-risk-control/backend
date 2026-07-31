package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 멀티에이전트 구매 리스크 점수 저장·조회 DTO.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE이므로 카멜케이스 필드는 {@code external_signal_score} 등으로
 * 직렬화된다.
 */
public final class ProcurementRiskDto {
    private ProcurementRiskDto() {}

    /**
     * {@code procurement_risk_assessments} 한 행. 입력 스냅샷·세부 점수·근거·버전을 함께 담는다.
     *
     * <p>세부 점수 3개를 전용 필드로 둔 것이 핵심이다. FastAPI 응답에서는 {@code riskReasons}
     * 문장과 {@code erpAssessment}/{@code contractAssessment} Map 안에 묻혀 있어 화면에서 파싱해야 했다.
     */
    public record Assessment(
            UUID assessmentId,

            @Schema(description = "외부신호 점수의 출처. 요청 본문으로 직접 넣은 경우 null")
            UUID analysisId,
            String newsId,
            Long materialId,
            String erpMaterialId,
            String erpSupplierId,

            @Schema(example = "LITHIUM", description = "자재 대분류 8종. 화면 카드·집계 단위")
            String materialCategory,
            String impactDomainFinal,
            OffsetDateTime assessedAt,

            @Schema(example = "82.0", description = "가중치 0.35") BigDecimal externalSignalScore,
            @Schema(example = "CRITICAL") String externalSignalLevel,
            @Schema(example = "45.0", description = "가중치 0.45") BigDecimal erpExposureScore,
            @Schema(example = "30.0", description = "가중치 0.20") BigDecimal contractGapScore,

            @Schema(example = "72.0") BigDecimal procurementRiskScore,
            @Schema(example = "CRITICAL", description = "NORMAL/WARNING/CRITICAL") String procurementRiskLevel,

            List<String> riskReasons,
            Map<String, Object> erpAssessment,
            Map<String, Object> contractAssessment,

            @Schema(example = "procurement-risk-v1", description = "적용된 가중치 조합 버전")
            String weightVersion,
            @Schema(description = "재고 소진 게이트로 가중합과 무관하게 심각으로 강제 상향됐는지")
            boolean stockoutGateApplied,
            Boolean reviewPassed,
            boolean llmUsed,
            boolean mock,
            OffsetDateTime createdAt
    ) {}
}

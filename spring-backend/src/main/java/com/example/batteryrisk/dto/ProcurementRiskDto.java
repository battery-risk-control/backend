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

            // 브리핑 본문·권고조치·계약근거·경고는 여기 없다. V29에서 ai_briefings로 옮겼다 —
            // 이 테이블은 V18 원래 목적대로 점수 이력만 남긴다.
    ) {}

    /**
     * 구매 리스크 평가 "완료 처리" 응답.
     *
     * <p>{@code alreadyAcknowledged}는 {@code ON CONFLICT DO NOTHING}으로 실제로는 아무 행도
     * 새로 생기지 않았을 때(이미 누가 처리해둔 경우) true — 클라이언트가 "방금 내가 처리함"과
     * "이미 처리돼 있었음"을 구분할 수 있게 한다.
     */
    public record AcknowledgeResponse(
            UUID assessmentId,
            Long acknowledgedBy,
            OffsetDateTime acknowledgedAt,
            boolean alreadyAcknowledged
    ) {}

    /**
     * 완료 처리 되돌리기 응답.
     *
     * <p>{@code notAcknowledged}는 되돌릴 기록이 애초에 없었다는 뜻이다 — 실패가 아니라
     * "이미 원하는 상태"다. 화면이 "되돌렸습니다"와 "원래 처리 안 돼 있었습니다"를 구분해
     * 안내할 수 있어야 하므로 200으로 내리고 이 플래그로 알린다({@code acknowledge}의
     * {@code alreadyAcknowledged}와 같은 방침).
     */
    public record UnacknowledgeResponse(
            UUID assessmentId,
            @Schema(description = "되돌릴 완료 기록이 없었으면 true. 이 경우에도 결과 상태는 같다.")
            boolean notAcknowledged
    ) {}
}

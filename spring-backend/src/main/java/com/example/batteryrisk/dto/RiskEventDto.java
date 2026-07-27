package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 프론트엔드 {@code src/api/types.ts}의 RiskEvent 계약을 그대로 반영하는 조회 응답 DTO.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE이므로 카멜케이스 필드는 자동으로 {@code risk_event_id},
 * {@code market_context} 등으로 직렬화되어 프론트 타입과 1:1로 맞는다.
 *
 * <p>grade("심각/주의/정상")·confidence_label("확정/경고/참고")·quality_check.status("pass/fail")는
 * 프론트가 문자열 리터럴로 기대하므로 enum이 아니라 String으로 둔다.
 */
public final class RiskEventDto {
    private RiskEventDto() {}

    public record RiskEvent(
            String riskEventId,
            @Schema(example = "심각", description = "심각/주의/정상") String grade,
            @Schema(example = "확정", description = "확정/경고/참고") String confidenceLabel,
            MarketContext marketContext,
            ErpView erpView,
            QualityCheck qualityCheck,
            RagView ragView,
            OutputArtifacts outputArtifacts
    ) {}

    /** 뉴스/시장 이벤트 맥락. 국가 특정이 불가능하면 country_*·coordinates는 null 가능. */
    public record MarketContext(
            String source,
            String material,
            String eventSummary,
            String countryCode,
            String countryName,
            Coordinates coordinates
    ) {}

    public record Coordinates(double lat, double lng) {}

    /** ERP 내부 관점(재고 소진일수·영향 자재·대체 조달 후보). 인증 사용자 전용 상세. */
    public record ErpView(
            int safetyStockDays,
            String affectedMaterialCode,
            List<String> altSourcingCandidates
    ) {}

    public record QualityCheck(
            @Schema(example = "pass", description = "pass/fail") String status,
            List<String> criteria,
            String reason
    ) {}

    public record RagView(
            String contractClauseSummary,
            List<String> negotiationPoints
    ) {}

    public record OutputArtifacts(
            @Schema(example = "json") String renderMode,
            String fileUrl,
            boolean fallbackToJson
    ) {}
}

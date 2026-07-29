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

    /**
     * 비로그인 공개 화면(Seq 23 글로벌 리스크 관제 지도)용 안전 subset.
     *
     * <p>프론트 {@code GlobalRiskBoardItem} 계약과 1:1. RiskEvent에서 등급·신뢰도·국가·좌표만 남기고
     * ERP 내부 상세({@code erp_view}·{@code quality_check}·{@code rag_view})는 <b>노출하지 않는다</b>.
     */
    public record RiskBoardItem(
            String riskEventId,
            String material,
            String grade,
            String confidenceLabel,
            String eventSummary,
            String countryCode,
            String countryName,
            Coordinates coordinates
    ) {}

    /**
     * 비로그인 공개 화면의 AI 기반 권고 조치 1건. 프론트 {@code AiRecommendation} 계약과 1:1.
     *
     * <p>{@link RiskBoardItem}과 같은 분석 집합에서 파생되므로 지도와 항상 같은 자재·등급을 가리킨다.
     * 공개 화면이라 {@code recommendation} 문구에 <b>공급사명·재고일수 같은 ERP 내부 상세는 넣지 않는다</b>
     * — 대체 후보는 개수까지만 표현한다.
     */
    public record AiRecommendationItem(
            String riskEventId,
            String material,
            String grade,
            String confidenceLabel,
            String recommendation
    ) {}

    /**
     * 비로그인 공개 화면의 실시간 뉴스 속보 1건. 프론트 {@code NewsFeedItem} 계약과 1:1.
     *
     * <p>{@code raw_events}의 수집 원본에서 만들며, 분석(F3)이 붙은 뉴스면 그 결과로 자재·신뢰도를
     * 채우고 아니면 제목 키워드로 자재만 추정한다 — 뉴스 원본 노출에 LLM 호출이 필요 없게 하기 위함이다.
     */
    public record NewsFeedItem(
            String riskEventId,
            @Schema(example = "2026-07-29", description = "수집일(Asia/Seoul)") String date,
            String material,
            @Schema(example = "GDELT") String source,
            String headline,
            String confidenceLabel
    ) {}
}

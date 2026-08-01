package com.example.batteryrisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1계층 구매팀 "리스크 모니터링" 화면(이벤트 목록 + 이벤트 상세)용 조회 응답 DTO.
 *
 * <p>전역 Jackson 설정이 SNAKE_CASE라 카멜케이스 필드는 {@code event_id}, {@code confidence_label}
 * 등으로 직렬화된다.
 *
 * <p><b>{@link RiskEventDto}와의 차이</b>: 저쪽은 <i>분석(analyses)</i>이 원천이고 지도 마커·공개
 * 속보가 소비한다. 이쪽은 <i>수집 뉴스(raw_events)</i>가 원천이다 — 화면이 "GDELT가 15분마다 모아
 * 번역한 기사 목록"이고, 각 기사에 분석·멀티에이전트 결과가 <b>붙었으면 붙은 만큼</b> 얹어
 * 보여주는 구조이기 때문이다. 그래서 식별자도 분석 UUID가 아니라 {@code raw_events.id}다.
 */
public final class RiskMonitoringDto {
    private RiskMonitoringDto() {}

    /**
     * 이벤트 목록 1건.
     *
     * <p><b>등급과 신뢰도는 서로 다른 것을 말한다.</b> {@code grade}는 "얼마나 위험한가",
     * {@code confidenceLabel}은 "어디까지 검증됐는가"다. 멀티에이전트(ERP·계약)를 아직 안 거친
     * 기사도 외부신호만으로 등급을 매겨 보여주되, 신뢰도 배지로 잠정값임을 밝힌다 —
     * 등급을 감추면 화면에서 아무것도 판단할 수 없고, 잠정값을 확정처럼 보여주면 오해를 낳는다.
     */
    public record EventItem(
            @Schema(example = "252", description = "raw_events.id. 상세 조회·분석 실행의 경로 변수로 쓴다.")
            long eventId,

            @Schema(example = "심각", description = "심각/주의/정상. 분석이 아직 없는 기사는 null")
            String grade,

            @Schema(example = "참고", description = "확정/경고/참고. '확정'이 아니면 화면이 잠정 배지를 함께 띄운다.")
            String confidenceLabel,

            @Schema(description = "멀티에이전트(ERP·계약)까지 끝나 종합 위험도가 나온 기사인지. "
                    + "false면 등급이 외부신호만으로 매겨진 잠정값이다.")
            boolean multiAgentCompleted,

            @Schema(description = "화면 표시용 헤드라인. 번역본이 있으면 한국어, 없으면 원문")
            String headline,

            @Schema(description = "항상 원문 헤드라인")
            String headlineOriginal,

            boolean translated,

            @Schema(example = "리튬", description = "영향 원자재 표기명. 특정 못 하면 \"기타\"")
            String material,

            @Schema(example = "CL") String countryCode,
            @Schema(example = "칠레") String countryName,

            @Schema(description = "수집 시각(UTC). 목록의 시간 표기에 쓴다.")
            Instant collectedAt,

            @Schema(example = "GDELT") String source
    ) {}

    /** 이벤트 상세 — 목록 항목에 뉴스 요약·외부신호·좌표·원문 링크·종합 평가를 더한 것. */
    public record EventDetail(
            long eventId,
            String grade,
            String confidenceLabel,
            boolean multiAgentCompleted,
            String headline,
            String headlineOriginal,
            boolean translated,

            @Schema(description = "LLM 추출이 만든 한국어 요약. 추출 전이면 null이라 화면이 블록을 숨긴다.")
            String summary,

            String material,

            @Schema(example = "PRODUCTION", description = "LLM이 판정한 영향 도메인")
            String impactDomain,

            String countryCode,
            String countryName,

            @Schema(description = "국가 기준점(수도) 좌표. 기사 발생 지점이 아니다 — 좌표 표에 없는 국가는 null")
            RiskEventDto.Coordinates coordinates,

            Instant collectedAt,
            String source,

            @Schema(description = "기사 원문 링크. 절대 http(s)가 아니면 null이라 화면이 버튼을 숨긴다.")
            String url,

            @Schema(description = "XGBoost 트리아지·LLM 추출을 거쳐 severity 규칙엔진이 먹은 입력값과 점수. "
                    + "분석이 아직 없는 기사는 null")
            ExternalSignal externalSignal,

            @Schema(description = "멀티에이전트 종합 평가. 아직 실행 전이면 null")
            ProcurementRisk procurementRisk,

            @Schema(description = "\"ERP·계약 영향 분석\" 버튼을 누를 수 있는지. false면 이유가 blockedReason에 담긴다.")
            boolean erpImpactAvailable,

            @Schema(example = "공급망과 무관한 뉴스로 판정되었습니다.", description = "실행 불가 사유. 가능하면 null")
            String erpImpactBlockedReason
    ) {}

    /**
     * 외부신호 — severity 규칙엔진(v0.2-realtime)의 입력값과 결과.
     *
     * <p>여기 값들은 {@code analyses}에 저장된 <b>실제로 점수를 만든</b> 값이다. 요청 시점의
     * override나 raw_events 원본과 다를 수 있다(FastAPI가 병합·기본값 적용 후 계산하므로).
     */
    public record ExternalSignal(
            @Schema(example = "-6.5", description = "GDELT Goldstein Scale(-10~+10). 낮을수록 갈등적 사건")
            Double goldsteinScale,

            @Schema(example = "-0.42", description = "LLM 추출 tone(-1~+1). 음수일수록 부정적. "
                    + "GDELT AvgTone이 아니라 추출 결과다 — 점수에 실제로 들어간 값이 이쪽이다.")
            Double toneScore,

            @Schema(example = "18", description = "같은 국가·같은 날 수집된 관련 뉴스 건수")
            Integer newsCount,

            @Schema(example = "60.0", description = "외부신호 위험 점수(0~100)")
            Double riskScore,

            @Schema(example = "CRITICAL", description = "CRITICAL/WARNING/NORMAL")
            String severity,

            @Schema(description = "판정 근거 코드. NOT_RELEVANT가 들어 있으면 공급망 무관 기사다.")
            List<String> reasonCodes
    ) {}

    /**
     * 멀티에이전트 종합 평가. 외부신호 0.35 + ERP 노출도 0.45 + 계약공백 0.20으로 합산된 결과다.
     *
     * <p><b>{@code completed}가 false면 이 점수를 등급으로 쓰지 않는다.</b> LangGraph는 KG 게이트에서
     * 매칭이 없거나 재고가 충분하면 ERP·계약 노드를 아예 타지 않고 조기 종료하는데, 그때도 행은
     * 남고 {@code riskScore}는 0, {@code riskLevel}은 NORMAL로 기록된다. 그 0을 "종합 판정 결과
     * 정상"으로 읽으면 <b>평가하지 못한 것을 안전하다고 표기</b>하게 된다 — 조기 종료 사유는
     * {@code riskReasons}에 담겨 있으니 화면은 그것을 보여주고 등급은 외부신호 기준을 유지한다.
     */
    public record ProcurementRisk(
            UUID assessmentId,

            @Schema(description = "ERP·계약 노드까지 실제로 실행돼 종합 점수가 나왔는지. "
                    + "false면 KG 게이트에서 조기 종료된 실행이라 riskScore·riskLevel은 의미가 없다.")
            boolean completed,

            @Schema(example = "CRITICAL", description = "CRITICAL/WARNING/NORMAL. completed=true일 때만 등급의 근거")
            String riskLevel,

            @Schema(example = "78.5") BigDecimal riskScore,
            @Schema(example = "60.0") BigDecimal externalSignalScore,

            @Schema(example = "82.0", description = "ERP 노출도. 조기 종료된 실행에서는 null")
            BigDecimal erpExposureScore,

            @Schema(example = "45.0", description = "계약 공백. 조기 종료된 실행에서는 null")
            BigDecimal contractGapScore,

            @Schema(description = "판단 근거. 조기 종료된 실행에서는 그 사유가 담긴다.")
            List<String> riskReasons,

            @Schema(description = "reviewer 노드의 근거·금지표현 검증 통과 여부")
            Boolean reviewPassed,

            OffsetDateTime assessedAt
    ) {}
}

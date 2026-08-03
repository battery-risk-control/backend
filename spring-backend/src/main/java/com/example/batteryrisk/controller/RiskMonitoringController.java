package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.RiskMonitoringDto;
import com.example.batteryrisk.service.RiskMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 1계층 구매팀 "리스크 모니터링" 화면 API.
 *
 * <p>GDELT 15분 스케줄러가 수집·번역한 뉴스를 목록으로 보여주고(좌측), 한 건을 고르면 상세를
 * 내려주며(우측), 화면에서 "ERP·계약 영향 분석"을 누르면 그 기사에 대해 멀티에이전트를 실행한다.
 * 판정 규칙과 등급 갱신 흐름은 {@link RiskMonitoringService} 참고.
 *
 * <p>ERP 내부 상세(노출도 점수·재고 판단 근거)를 포함하므로 인증(Bearer)을 요구한다.
 * 비로그인 화면이 쓰는 축약 응답은 {@code /api/v1/public/news-feed}로 따로 있다.
 */
/*
 * 권한 정책(2026-08-02): 클래스 단위는 <b>조회</b> 기준이라 구매팀 + 경영기획팀을 허용하고,
 * 실행·생성 계열 메서드만 구매팀으로 좁힌다. docs/api-inventory-for-ia.md §2의 계층 배치안이
 * 경영기획팀에 "⚠️ 제한 제공"을 주기로 되어 있어서, 클래스 전체를 구매팀 전용으로 막으면
 * 그 계획을 지금 "전면 차단"으로 확정해버리기 때문이다. 경영진은 집계 API만 쓰므로 여기서 제외된다.
 *
 * <p>HTTP 메서드가 아니라 <b>동작의 성격</b>으로 나눈다 — 계약 조항 검색처럼 POST지만 읽기인
 * 엔드포인트가 있어서, verb로 나누면 조회를 실행으로 오분류한다.
 */
@RestController
@RequestMapping("/api/v1/risk-monitoring")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('PURCHASING','STRATEGY')")
@Validated
public class RiskMonitoringController {
    private final RiskMonitoringService riskMonitoringService;

    public RiskMonitoringController(RiskMonitoringService riskMonitoringService) {
        this.riskMonitoringService = riskMonitoringService;
    }

    @Operation(
            summary = "리스크 이벤트 목록 (구매팀)",
            description = "수집·번역된 뉴스를 최신순으로 반환한다. 같은 기사는 제목 기준으로 한 번만 나가고, "
                    + "자재가 특정되지 않은 기사(공급망 무관)는 제외한다. grade는 멀티에이전트가 끝난 기사면 "
                    + "종합 위험도, 아니면 외부신호 기준 잠정값이며 multi_agent_completed로 구분한다.")
    @GetMapping("/events")
    public ApiResponse<List<RiskMonitoringDto.EventItem>> events(
            @Parameter(description = "심각/주의/정상. 생략하면 전체", example = "심각")
            @RequestParam(required = false) String grade,

            @Parameter(description = "ISO 3166-1 alpha-2 국가 코드. 생략하면 전체", example = "CL")
            @RequestParam(required = false) String country,

            @Parameter(description = "자재 표기명(리튬) 또는 대분류(LITHIUM). 생략하면 전체", example = "리튬")
            @RequestParam(required = false) String material,

            @Parameter(description = "최근 N일(1~180). 화면 기본 필터는 7일이다.", example = "7")
            @RequestParam(defaultValue = "7") @Min(1) @Max(180) int days,

            @Parameter(description = "노출 건수(1~200)", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ApiResponse.ok(riskMonitoringService.list(grade, country, material, days, limit));
    }

    @Operation(
            summary = "리스크 이벤트 상세 (구매팀)",
            description = "뉴스 요약·영향 원자재·외부신호·좌표·원문 링크와, 멀티에이전트가 끝난 기사면 "
                    + "종합 평가(세부 점수 3개·판단 근거)를 함께 반환한다.")
    @GetMapping("/events/{eventId}")
    public ApiResponse<RiskMonitoringDto.EventDetail> event(
            @Parameter(description = "목록의 event_id", example = "252")
            @PathVariable long eventId) {
        return ApiResponse.ok(riskMonitoringService.detail(eventId));
    }

    @Operation(
            summary = "[폐기 예정] ERP·계약 영향 분석 실행 (구매팀)",
            deprecated = true,
            description = """
                    **이 API는 폐기 예정이다. 새로 연결하지 말 것.**

                    멀티에이전트를 실행하고 종합 평가를 저장하지만 **NEWS 브리핑은 저장하지 않는다.**
                    확정 판정 기준이 "완결된 NEWS 브리핑 존재"로 통일된 뒤(2026-08-03), 이 경로로
                    실행하면 LLM 비용은 발생하는데 결과가 어느 화면에도 확정으로 보이지 않는다 —
                    브리핑 본문도 남지 않아 다시 열어볼 수 없다.

                    대신 AI 브리핑 화면 경로를 쓸 것:
                    POST /api/v1/ai-briefing/briefings (source=NEWS, ref={eventId})
                    이쪽은 같은 멀티에이전트를 돌리고 ai_briefings에 본문까지 저장해 확정이 된다.

                    두 프론트엔드 모두 이 API를 호출하지 않는 것을 확인했다(2026-08-03).
                    제거하지 않고 남겨 두는 이유는 외부에서 직접 부르는 경로가 있을 수 있어서다.
                    """)
    @Deprecated(since = "2026-08-03")
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping("/events/{eventId}/erp-impact")
    public ApiResponse<RiskMonitoringDto.EventDetail> runErpImpact(
            @Parameter(description = "목록의 event_id", example = "252")
            @PathVariable long eventId,

            @Parameter(description = "브리핑 문구 생성에 LLM을 쓸지. 등급 갱신에는 필요 없어 기본 false다.")
            @RequestParam(defaultValue = "false") boolean useLlm) {
        return ApiResponse.ok(riskMonitoringService.runErpImpact(eventId, useLlm));
    }
}

package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.RiskMonitoringDto;
import com.example.batteryrisk.service.RiskMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RestController
@RequestMapping("/api/v1/risk-monitoring")
@SecurityRequirement(name = "bearerAuth")
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
            summary = "ERP·계약 영향 분석 실행 (구매팀)",
            description = """
                    이 기사 한 건에 대해 멀티에이전트(ERP Agent · 계약 RAG Agent · 위험도 합산 ·
                    브리핑 · 검증)를 실행하고 갱신된 상세를 반환한다. 실행 후에는 등급이 종합 위험도
                    기준으로 바뀌고 신뢰도가 '확정'이 되며, 결과가 저장되므로 다시 조회해도 유지된다.

                    실행할 수 없는 기사(분석 전·공급망 무관·국가 미상 등)는 422와 함께 사유를 돌려준다.
                    상세 응답의 erp_impact_available·erp_impact_blocked_reason으로 미리 알 수 있다.
                    """)
    @PostMapping("/events/{eventId}/erp-impact")
    public ApiResponse<RiskMonitoringDto.EventDetail> runErpImpact(
            @Parameter(description = "목록의 event_id", example = "252")
            @PathVariable long eventId,

            @Parameter(description = "브리핑 문구 생성에 LLM을 쓸지. 등급 갱신에는 필요 없어 기본 false다.")
            @RequestParam(defaultValue = "false") boolean useLlm) {
        return ApiResponse.ok(riskMonitoringService.runErpImpact(eventId, useLlm));
    }
}

package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.AiBriefingDto;
import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.service.AiBriefingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 1계층 구매팀 "AI 브리핑" 화면 API.
 *
 * <p>앞선 세 화면에서 버튼을 누르면 그 자리에서 분석이 도는 게 아니라 이 화면으로 <b>이동</b>한다.
 * 이동하면서 넘긴 대상({@code source}·{@code ref})으로 상단 "분석 대상 · ERP 연결"을 채우고
 * ({@code GET /context}), "LLM 브리핑 생성"을 눌렀을 때 비로소 멀티에이전트가 돈다
 * ({@code POST /briefings}). 결과는 저장되므로 "최근 브리핑"으로 남고 나중에 다시 열어볼 수 있다.
 *
 * <pre>
 *   리스크 모니터링 · 뉴스   → source=NEWS     ref=event_id
 *   원자재 위험 · 자재       → source=MATERIAL ref=erp_material_id
 *   계약·RAG · 계약          → source=CONTRACT ref=contract_id
 * </pre>
 *
 * <p>ERP 내부 상세(재고·의존도)와 계약 조항 원문을 그대로 노출하므로 인증(Bearer)을 요구한다.
 * 판정과 실행 흐름은 {@link AiBriefingService} 참고.
 */
/*
 * 권한 정책(2026-08-02): 클래스 단위는 <b>조회</b> 기준이라 구매팀 + 경영기획팀을 허용하고,
 * 실행·생성 계열 메서드만 구매팀으로 좁힌다. 근거는 RiskMonitoringController의 같은 주석 참고.
 */
@RestController
@RequestMapping("/api/v1/ai-briefing")
@SecurityRequirement(name = "bearerAuth")
@Validated
@PreAuthorize("hasAnyRole('PURCHASING','STRATEGY')")
public class AiBriefingController {
    private final AiBriefingService aiBriefingService;

    public AiBriefingController(AiBriefingService aiBriefingService) {
        this.aiBriefingService = aiBriefingService;
    }

    @Operation(
            summary = "브리핑 대상 프리필 (구매팀 AI 브리핑)",
            description = """
                    앞 화면에서 넘어온 대상의 "분석 대상"(제목·news_id)과 "ERP 연결"(자재·공급사·계약),
                    외부신호 등급·점수를 반환한다. **멀티에이전트를 돌리지 않는다** — 화면에 들어오기만
                    해도 LLM 비용이 나가면 안 되기 때문이다.

                    generate_available로 'LLM 브리핑 생성' 버튼을 미리 비활성화할 수 있다. 그 판정은
                    앞 화면들의 erp_impact_available·briefing_available과 같은 것을 그대로 쓴다.
                    """)
    @GetMapping("/context")
    public ApiResponse<AiBriefingDto.Context> context(
            @Parameter(description = "NEWS/MATERIAL/CONTRACT", example = "NEWS")
            @RequestParam String source,

            @Parameter(description = "event_id · erp_material_id · contract_id", example = "252")
            @RequestParam String ref) {
        return ApiResponse.ok(aiBriefingService.context(source, ref));
    }

    @Operation(
            summary = "LLM 브리핑 생성 (구매팀 AI 브리핑)",
            description = """
                    이 대상에 대해 멀티에이전트(ERP Agent · 계약 RAG Agent · 위험도 합산 · 브리핑 ·
                    검증)를 실행하고, 브리핑 본문·권고조치·ERP 노출 근거·계약 근거를 ai_briefings에
                    저장한 뒤 반환한다. 점수는 기존대로 procurement_risk_assessments에도 남는다.

                    use_llm은 생략하면 **true**다 — 버튼 이름이 'LLM 브리핑 생성'이라 누르는 순간
                    LLM 문구 생성까지 도는 것이 화면의 약속이다(다른 화면들은 기본 false).

                    실행할 수 없는 대상은 422와 함께 사유를 돌려준다. GET /context의
                    generate_blocked_reason으로 미리 알 수 있다.
                    """)
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping("/briefings")
    public ApiResponse<AiBriefingDto.BriefingDetail> generate(
            @Valid @RequestBody AiBriefingDto.GenerateRequest request) {
        return ApiResponse.ok(aiBriefingService.generate(request));
    }

    @Operation(
            summary = "최근 브리핑 목록 (구매팀 AI 브리핑)",
            description = "저장된 브리핑을 최신순으로 반환한다. 누가 만들었든 팀 전체가 같은 목록을 본다. "
                    + "카드에 필요한 값만 담겨 있어 본문·근거는 오지 않는다 — 상세는 briefing_id로 따로 조회한다. "
                    + "필터와 페이징은 모두 서버에서 적용된다. 화면에서 거르면 '먼저 자르고 그 안에서 "
                    + "필터'가 되어 뒷 페이지 항목이 사라진다.")
    @GetMapping("/briefings")
    public ApiResponse<PageResponse<AiBriefingDto.BriefingListItem>> recent(
            @Parameter(description = "대상 유형. NEWS/MATERIAL/CONTRACT. 생략하면 전체", example = "NEWS")
            @RequestParam(required = false) String source,
            @Parameter(description = "위험 등급. CRITICAL/WARNING/NORMAL. 생략하면 전체. "
                    + "평가 미완료(composite=false) 브리핑은 어느 등급으로도 걸리지 않는다.")
            @RequestParam(required = false) String level,
            @Parameter(description = "검증 상태. PASSED(검증 통과)/FAILED(검토 필요)/PENDING(미검증). 생략하면 전체")
            @RequestParam(required = false) String reviewStatus,
            @Parameter(description = "최근 N일. 생략하면 기간 제한 없음", example = "7")
            @RequestParam(required = false) @Min(1) Integer days,
            @Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
            @RequestParam(required = false) @Min(0) Integer page,
            @Parameter(description = "페이지 크기(1~50)", example = "5")
            @RequestParam(required = false) @Min(1) @Max(50) Integer size) {
        return ApiResponse.ok(
                aiBriefingService.recent(source, level, reviewStatus, days, page, size));
    }

    @Operation(
            summary = "브리핑 상세 보기 (구매팀 AI 브리핑)",
            description = "저장된 브리핑을 생성 직후와 같은 모양으로 반환한다. 화면이 방금 만든 것과 "
                    + "다시 열어본 것을 다르게 그릴 이유가 없어 응답 타입을 하나로 뒀다.")
    @GetMapping("/briefings/{briefingId}")
    public ApiResponse<AiBriefingDto.BriefingDetail> get(
            @Parameter(description = "목록의 briefing_id")
            @PathVariable UUID briefingId) {
        return ApiResponse.ok(aiBriefingService.get(briefingId));
    }
}

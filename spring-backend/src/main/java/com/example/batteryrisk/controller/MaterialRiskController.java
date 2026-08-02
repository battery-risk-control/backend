package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.service.MaterialRiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1계층 구매팀 "원자재 위험" 화면 API.
 *
 * <p>ERP 재고·발주·공급사 구조를 자재별로 평가해 상단 KPI와 목록을 내려주고(overview), 한 자재를
 * 고르면 상세를 준다. 상세 하단의 버튼 두 개가 각각 계약 RAG 근거 검색과 AI 브리핑 생성이다.
 * 판정 규칙과 ERP Exposure Agent 연동은 {@link MaterialRiskService} 참고.
 *
 * <p>ERP 내부 상세(재고 수량·공급사 의존도·계약 조항)를 그대로 노출하므로 인증(Bearer)을 요구한다.
 * 비로그인 화면에는 이에 대응하는 축약 응답이 없다 — 조달 구조는 공개 대상이 아니다.
 */
/*
 * 권한 정책(2026-08-02): 클래스 단위는 <b>조회</b> 기준이라 구매팀 + 경영기획팀을 허용하고,
 * 실행·생성 계열 메서드만 구매팀으로 좁힌다. 근거는 RiskMonitoringController의 같은 주석 참고.
 */
@RestController
@RequestMapping("/api/v1/material-risk")
@SecurityRequirement(name = "bearerAuth")
@Validated
@PreAuthorize("hasAnyRole('PURCHASING','STRATEGY')")
public class MaterialRiskController {
    private final MaterialRiskService materialRiskService;

    public MaterialRiskController(MaterialRiskService materialRiskService) {
        this.materialRiskService = materialRiskService;
    }

    @Operation(
            summary = "원자재 위험 개요 (구매팀)",
            description = """
                    상단 KPI(평가 자재·심각 건수·평균 재고일수·데이터 품질)와 자재별 위험 목록을
                    한 번에 반환한다. 둘이 같은 계산 결과에서 나오므로 엔드포인트를 나누지 않았다.

                    점수·등급은 멀티에이전트의 ERP Exposure Agent가 계산한 값이다. 재고 데이터가
                    없거나 Agent 호출이 실패한 자재는 목록에서 빠지지 않고 score=null과
                    unavailable_reason을 달고 맨 뒤에 온다.

                    자재 수만큼 ERP Exposure Agent를 호출하므로 60초 캐시가 붙어 있다. 계산 시각은
                    응답의 as_of에 담겨 있고, 화면의 "새로고침"은 refresh=true로 캐시를 건너뛴다.
                    """)
    @GetMapping("/overview")
    public ApiResponse<MaterialRiskDto.Overview> overview(
            @Parameter(description = "캐시를 건너뛰고 다시 계산할지. 화면의 새로고침 버튼이 쓴다.")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(materialRiskService.overview(refresh));
    }

    @Operation(
            summary = "자재 상세 (구매팀)",
            description = "ERP 노출 정보(재고·안전재고·예상 입고·공급 공백·의존도), 주 공급사, 연결 계약, "
                    + "ERP 노출도 세부 점수 5개를 반환한다. briefing_available로 'AI 브리핑 생성' "
                    + "버튼을 미리 비활성화할 수 있다.")
    @GetMapping("/materials/{erpMaterialId}")
    public ApiResponse<MaterialRiskDto.MaterialDetail> detail(
            @Parameter(description = "목록의 erp_material_id", example = "MAT-CO-SULF")
            @PathVariable String erpMaterialId) {
        return ApiResponse.ok(materialRiskService.detail(erpMaterialId));
    }

    @Operation(
            summary = "계약 RAG 근거 보기 (구매팀)",
            description = """
                    이 자재의 연결 계약에서, 지금 처한 상황에 해당하는 조항을 찾아 반환한다.
                    질의는 ERP Exposure Agent가 만든 질문(납기 지연 통보·불가항력·대체 공급사 제한 등)을
                    그대로 쓴다 — 어떤 상황에 무엇을 물어야 하는지는 erp_rules.yaml에 이미 정의돼 있다.

                    연결 계약이 없는 자재는 422로 막는다.
                    """)
    @PostMapping("/materials/{erpMaterialId}/contract-evidence")
    public ApiResponse<MaterialRiskDto.ContractEvidence> contractEvidence(
            @Parameter(description = "목록의 erp_material_id", example = "MAT-CO-SULF")
            @PathVariable String erpMaterialId) {
        return ApiResponse.ok(materialRiskService.contractEvidence(erpMaterialId));
    }

    /*
     * AI 브리핑 생성은 여기에 없다(2026-08-02 제거). 화면의 "AI 브리핑 생성" 버튼은
     * /purchasing/ai-briefing?source=MATERIAL&ref={erpMaterialId} 로 이동하고, 실행·저장·근거
     * 표시는 전부 AiBriefingController(POST /api/v1/ai-briefing/briefings)가 맡는다.
     *
     * 예전에는 이 컨트롤러에도 같은 일을 하는 POST .../briefing이 있었는데, 화면이 중앙 브리핑
     * 화면으로 옮겨간 뒤 호출자가 하나도 없었다. 브리핑 API가 둘로 보이면 "어느 쪽이 진짜인지"가
     * 계속 헷갈리고, 저장 경로가 갈라져 같은 자재의 브리핑이 두 군데에 남을 수 있다.
     *
     * 실행 가능 여부만 상세 응답의 briefing_available로 계속 내려준다 — 못 돌릴 대상으로
     * 이동시키면 빈 화면만 보여주는 셈이기 때문이다.
     */
}

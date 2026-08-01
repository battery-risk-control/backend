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
@RestController
@RequestMapping("/api/v1/material-risk")
@SecurityRequirement(name = "bearerAuth")
@Validated
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
                    """)
    @GetMapping("/overview")
    public ApiResponse<MaterialRiskDto.Overview> overview() {
        return ApiResponse.ok(materialRiskService.overview());
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

    @Operation(
            summary = "AI 브리핑 생성 (구매팀)",
            description = """
                    이 자재에 대해 멀티에이전트(ERP Agent · 계약 RAG Agent · 위험도 합산 · 브리핑 ·
                    검증)를 실행하고 결과를 반환한다. 결과는 procurement_risk_assessments에 저장된다.

                    이 화면에는 뉴스가 없으므로 외부신호는 DB에 이미 저장된 같은 자재 대분류의 최신
                    분석(analyses)에서 끌어온다. 응답의 source_analysis_id·source_headline이 그 출처다.
                    쓸 분석이 없으면 422와 함께 사유를 돌려준다 — 상세 응답의 briefing_available로
                    미리 알 수 있다.
                    """)
    @PostMapping("/materials/{erpMaterialId}/briefing")
    public ApiResponse<MaterialRiskDto.Briefing> briefing(
            @Parameter(description = "목록의 erp_material_id", example = "MAT-CO-SULF")
            @PathVariable String erpMaterialId,

            @Parameter(description = "브리핑 문구 생성에 LLM을 쓸지. 등급 산출에는 필요 없어 기본 false다.")
            @RequestParam(defaultValue = "false") boolean useLlm) {
        return ApiResponse.ok(materialRiskService.briefing(erpMaterialId, useLlm));
    }
}

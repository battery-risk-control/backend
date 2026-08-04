package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.service.ContractRagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 1계층 구매팀 "계약 · RAG 검색" 화면 API.
 *
 * <p>검색창에 문장을 넣으면 ChromaDB에 적재된 계약 조항을 의미검색해 유사도 순으로 보여주고,
 * 조항을 고르면 그 계약의 원본 문서·임베딩 상태를 내려준다. 화면에서 계약서를 추가로 올리거나
 * 다시 임베딩할 수 있다.
 *
 * <p><b>여기는 RAG 검색·문서 관리까지만 한다.</b> 멀티에이전트(supervisor·ERP·계약 RAG Agent)는
 * 돌지 않는다 — 화면의 "AI 브리핑 생성"은 실행이 아니라 {@code /purchasing/ai-briefing?source=CONTRACT}
 * 로의 <b>이동</b>이고, 실제 분석은 {@link AiBriefingController}가 맡는다. 2026-08-02에 여기 있던
 * {@code POST /briefings}를 걷어냈다 — 같은 멀티에이전트를 두 경로가 각자 부르면서 이쪽 결과만
 * {@code ai_briefings}에 안 남아, 실행 이력이 "최근 브리핑" 목록에서 사라지는 문제가 있었다.
 *
 * <p>계약 내부 정보(단가·물량 조항 원문)를 그대로 노출하므로 인증(Bearer)을 요구한다.
 * 경로를 {@code /api/v1/contract-rag}로 둔 것은 {@code /api/v1/contracts}(대시보드 계약 목록)와
 * {@code /api/v1/rag}(멀티에이전트 검색)를 각각 그대로 두기 위해서다.
 */
/*
 * 권한 정책(2026-08-02): 클래스 단위는 <b>조회</b> 기준이라 구매팀 + 경영기획팀을 허용하고,
 * 실행·생성 계열 메서드만 구매팀으로 좁힌다. 근거는 RiskMonitoringController의 같은 주석 참고.
 */
@RestController
@RequestMapping("/api/v1/contract-rag")
@SecurityRequirement(name = "bearerAuth")
@Validated
@PreAuthorize("hasAnyRole('PURCHASING','STRATEGY')")
public class ContractRagController {
    private final ContractRagService contractRagService;

    public ContractRagController(ContractRagService contractRagService) {
        this.contractRagService = contractRagService;
    }

    @Operation(
            summary = "계약 목록 (구매팀 계약·RAG)",
            description = "검색 범위를 좁힐 때 쓰는 계약 목록이다. 기본값은 ChromaDB에 실제로 적재된 "
                    + "계약만 내려준다 — 적재되지 않은 계약을 고르면 검색이 항상 비기 때문이다.")
    @GetMapping("/contracts")
    public ApiResponse<List<ContractRagDto.ContractSummary>> contracts(
            @Parameter(description = "true면 문서가 적재되지 않은 계약도 포함한다.")
            @RequestParam(name = "include_unindexed", defaultValue = "false") boolean includeUnindexed) {
        return ApiResponse.ok(contractRagService.contracts(includeUnindexed));
    }

    @Operation(
            summary = "계약 조항 검색 (구매팀 계약·RAG)",
            description = """
                    검색어와 의미가 가까운 계약 조항을 유사도 순으로 반환한다.
                    contract_id를 비우면 **전체 계약**을 훑고, 지정하면 그 계약으로 좁힌다(scope로 구분).

                    각 결과에는 조항 제목(청크 머리에서 추출), 유사도, 페이지, 원문과 함께
                    그 조항이 속한 계약 요약이 붙는다. mock=true면 임베딩이 mock이라
                    유사도 점수에 의미가 없다는 뜻이다.
                    """)
    @PostMapping("/search")
    public ApiResponse<ContractRagDto.SearchResponse> search(
            @Valid @RequestBody ContractRagDto.SearchRequest request) {
        return ApiResponse.ok(contractRagService.search(request));
    }

    @Operation(
            summary = "계약 문서 상세 (구매팀 계약·RAG)",
            description = "계약 메타(계약번호·기간·공급사·자재)와 적재된 원본 문서 목록, 임베딩 종류·버전을 "
                    + "반환한다. briefing_available로 'AI 브리핑 생성'을 지금 누를 수 있는지 미리 알 수 있다.")
    @GetMapping("/contracts/{contractId}")
    public ApiResponse<ContractRagDto.ContractDetail> contract(
            @Parameter(description = "내부 계약 PK", example = "11")
            @PathVariable long contractId) {
        return ApiResponse.ok(contractRagService.contract(contractId));
    }

    @Operation(
            summary = "계약서 추가 업로드 (구매팀 계약·RAG)",
            description = "PDF/TXT 계약 문서를 이 계약에 붙여 청킹·임베딩한 뒤 ChromaDB에 적재한다. "
                    + "공급사·자재 ID는 계약에서 자동으로 채우므로 화면은 파일만 보내면 된다. "
                    + "같은 계약에 같은 내용이 이미 있으면 duplicate=true로 돌아오고 재적재하지 않는다.")
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping(value = "/contracts/{contractId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContractRagDto.UploadResponse> upload(
            @Parameter(description = "내부 계약 PK", example = "11")
            @PathVariable long contractId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(contractRagService.upload(contractId, file));
    }

    @Operation(
            summary = "문서 재처리 (구매팀 계약·RAG)",
            description = "이 계약에 달린 문서를 전부 다시 임베딩해 ChromaDB에 올린다. 임베딩 모델을 "
                    + "바꿨거나 적재가 깨졌을 때 쓴다. 일부 문서가 실패해도 나머지는 계속 처리하고 "
                    + "문서별 성공/실패를 함께 반환한다.")
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping("/contracts/{contractId}/reprocess")
    public ApiResponse<ContractRagDto.ReprocessResponse> reprocess(
            @Parameter(description = "내부 계약 PK", example = "11")
            @PathVariable long contractId) {
        return ApiResponse.ok(contractRagService.reprocess(contractId));
    }

}

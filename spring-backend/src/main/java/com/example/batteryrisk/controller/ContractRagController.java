package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.service.ContractRagService;
import com.example.batteryrisk.service.DocumentService;
import com.example.batteryrisk.service.OutboundDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
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
    private final DocumentService documentService;
    private final OutboundDocumentService outboundDocumentService;

    public ContractRagController(
            ContractRagService contractRagService, DocumentService documentService,
            OutboundDocumentService outboundDocumentService) {
        this.outboundDocumentService = outboundDocumentService;
        this.contractRagService = contractRagService;
        this.documentService = documentService;
    }

    @Operation(
            summary = "계약 목록 (구매팀 계약·RAG)",
            description = "검색 범위를 좁힐 때 쓰는 계약 목록이다. 기본값은 ChromaDB에 실제로 적재된 "
                    + "계약만 내려준다 — 적재되지 않은 계약을 고르면 검색이 항상 비기 때문이다.")
    // 비로그인 대시보드(/public/*)가 구매팀 화면과 동일하게 보여주기 위해 조회만 공개(2026-08-05).
    // 업로드·재처리(쓰기)는 아래 두 메서드처럼 여전히 hasRole('PURCHASING')로 막힌다.
    @PreAuthorize("permitAll()")
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
    @PreAuthorize("permitAll()")
    @PostMapping("/search")
    public ApiResponse<ContractRagDto.SearchResponse> search(
            @Valid @RequestBody ContractRagDto.SearchRequest request) {
        return ApiResponse.ok(contractRagService.search(request));
    }

    @Operation(
            summary = "계약 문서 상세 (구매팀 계약·RAG)",
            description = "계약 메타(계약번호·기간·공급사·자재)와 적재된 원본 문서 목록, 임베딩 종류·버전을 "
                    + "반환한다. briefing_available로 'AI 브리핑 생성'을 지금 누를 수 있는지 미리 알 수 있다.")
    @PreAuthorize("permitAll()")
    @GetMapping("/contracts/{contractId}")
    public ApiResponse<ContractRagDto.ContractDetail> contract(
            @Parameter(description = "내부 계약 PK", example = "11")
            @PathVariable long contractId) {
        return ApiResponse.ok(contractRagService.contract(contractId));
    }

    @Operation(
            summary = "아웃바운드(제품 납품) 계약 문서 상세 (구매팀 계약·RAG)",
            description = "제품 납품 계약(CTR-OUT-XXX)의 메타(제품·고객사)와 적재된 문서 목록을 반환한다. "
                    + "인바운드 상세({contractId})와 계약 PK 번호가 겹치므로 경로를 분리했다. "
                    + "납품계약은 AI 브리핑 대상이 아니라 briefing_available은 항상 false다.")
    @PreAuthorize("permitAll()")
    @GetMapping("/outbound-contracts/{outboundContractId}")
    public ApiResponse<ContractRagDto.ContractDetail> outboundContract(
            @Parameter(description = "내부 아웃바운드 계약 PK", example = "2")
            @PathVariable long outboundContractId) {
        return ApiResponse.ok(contractRagService.outboundContract(outboundContractId));
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

    @Operation(
            summary = "납품계약서 추가 업로드 (구매팀 계약·RAG)",
            description = "PDF/TXT 납품(아웃바운드) 계약 문서를 이 계약에 붙여 청킹·임베딩한 뒤 ChromaDB에 적재한다. "
                    + "제품·고객사 ID는 계약에서 자동으로 채우므로 화면은 파일만 보내면 된다. 인바운드 업로드와 "
                    + "동일하게 같은 내용이면 duplicate=true, 기존 문서가 있으면 그 자리를 교체한다.")
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping(value = "/outbound-contracts/{outboundContractId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContractRagDto.UploadResponse> uploadOutbound(
            @Parameter(description = "내부 아웃바운드 계약 PK", example = "2")
            @PathVariable long outboundContractId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(contractRagService.uploadOutbound(outboundContractId, file));
    }

    @Operation(
            summary = "납품계약 문서 재처리 (구매팀 계약·RAG)",
            description = "이 납품(아웃바운드) 계약에 달린 문서를 전부 다시 임베딩해 ChromaDB에 올린다. "
                    + "일부 문서가 실패해도 나머지는 계속 처리하고 문서별 성공/실패를 함께 반환한다.")
    @PreAuthorize("hasRole('PURCHASING')")
    @PostMapping("/outbound-contracts/{outboundContractId}/reprocess")
    public ApiResponse<ContractRagDto.ReprocessResponse> reprocessOutbound(
            @Parameter(description = "내부 아웃바운드 계약 PK", example = "2")
            @PathVariable long outboundContractId) {
        return ApiResponse.ok(contractRagService.reprocessOutbound(outboundContractId));
    }

    @Operation(
            summary = "계약서 원본 다운로드 (구매팀 계약·RAG)",
            description = "document_id로 저장된 계약서 원본 파일(PDF/TXT)을 그대로 내려준다. AI 브리핑의 "
                    + "'계약서에서 확인된 근거'가 참조하는 계약서를 확인용으로 받는 경로다. 계약 원문을 "
                    + "노출하므로 조회지만 인증(Bearer)을 유지한다.")
    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(
            @Parameter(description = "계약서 문서 ID", example = "con_2cebc9cba32446c9a8dbed9f8368b82e")
            @PathVariable String documentId) {
        // 납품(outbound) 계약 문서는 별도 테이블/서비스라 접두(outcon_)로 갈라 보낸다.
        // 그 외(con_/po_/…)는 기존 매입(inbound) 다운로드 경로.
        DocumentDto.DownloadFile file = documentId.startsWith("outcon_")
                ? outboundDocumentService.download(documentId)
                : documentService.download(documentId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(file.fileName()))
                .contentLength(file.content().length)
                .body(file.content());
    }

    /**
     * ASCII 파일명과 함께 규격대로 {@code filename*}(UTF-8)을 낸다 — 계약서 파일명에 한글이 섞여도
     * 브라우저가 올바로 저장하도록. AiBriefingController.attachment와 같은 방식이다.
     */
    private static String attachment(String fileName) {
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }

}

package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.dto.OutboundDocumentDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.exception.GlobalExceptionHandler.RagSearchException;
import com.example.batteryrisk.repository.ContractRagRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1계층 구매팀 "계약 · RAG 검색" 화면의 조항 검색 · 계약 문서 조회 · 업로드 · 재처리를 담당한다.
 *
 * <p><b>여기서 멀티에이전트는 돌지 않는다.</b> 이 서비스가 하는 일은 RAG 검색과 문서 관리까지다 —
 * supervisor · ERP Agent · 계약 RAG Agent는 {@link AiBriefingService}(화면의 "AI 브리핑")가 실행한다.
 * 2026-08-02에 여기 있던 {@code briefing()}을 걷어냈다: 같은 멀티에이전트를 두 서비스가 각자 부르면
 * 이쪽 결과만 {@code ai_briefings}에 안 남아 실행 이력이 갈라졌다. 브리핑에 필요한 계약 판정
 * ({@link #contract})과 관련 뉴스 조회({@link ContractRagRepository#findLatestRelatedNews})는
 * {@code AiBriefingService}가 이 서비스·리포지토리를 <b>재사용</b>하므로 규칙은 한 벌만 남는다.
 *
 * <p><b>기존 RAG 코드는 건드리지 않는다.</b> 검색은 FastAPI에 새로 만든
 * {@code /api/v1/contract-rag/search}를 부르고(필터 없이 전체 계약 검색이 가능한 경로),
 * 적재·재처리는 {@link DocumentService}를 <b>있는 그대로 호출만</b> 한다.
 *
 * <p>화면이 요구하지만 저장소에는 없는 값이 하나 있다 — <b>조항 제목</b>이다. ChromaDB 청크에는
 * 제목 필드가 없어서 청크 본문 머리(“Article 4 / DELIVERY AND PENALTY”)에서 뽑아 만든다.
 * ERP 연결 시드가 영문 계약서라 표제도 영문이므로 {@link #CLAUSE_LABELS_KO}로 한글 라벨을
 * 입혀 “제4조 · 납기 및 지연 위약금” 형태로 내려준다(매핑에 없으면 원문 그대로 폴백).
 */
@Service
public class ContractRagService {
    private static final Logger log = LoggerFactory.getLogger(ContractRagService.class);
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

    /** 화면 업로드가 만드는 문서 유형. contract_documents의 CHECK 제약을 따른다. */
    private static final String UPLOAD_DOCUMENT_TYPE = "CONTRACT";

    /** 브리핑 문구 생성 여부와 무관하게 등급 갱신은 항상 돈다 — 기본은 LLM off. */
    private static final String SEARCH_SOURCE = "chroma";

    /**
     * 조항 머리에서 번호와 표제를 뽑는 패턴.
     * <ul>
     *   <li>영문 시드: {@code "Article 4\nDELIVERY AND PENALTY"}</li>
     *   <li>한글 계약서: {@code "제7조 (지식재산권)"} 또는 {@code "제7조 지식재산권"}</li>
     * </ul>
     */
    private static final Pattern EN_CLAUSE = Pattern.compile(
            "^\\s*Article\\s+(\\d+(?:\\.\\d+)*)\\s*[\\r\\n:.\\-]+\\s*([^\\r\\n]{0,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KO_CLAUSE = Pattern.compile(
            "^\\s*(제\\s*\\d+\\s*조(?:의\\s*\\d+)?)\\s*[\\s(:.\\-]*([^\\r\\n)]{0,80})");

    /**
     * 영문 표제 → 한글 라벨. ERP 연결 시드(data/RAG_DATA/erp_aligned) 전체에 등장하는 표제가
     * 27종뿐이라 매핑 하나로 화면 문구를 한글로 맞출 수 있다. LLM 번역을 쓰지 않는 이유는
     * 검색 한 번마다 비용·지연이 붙고 같은 조항이 매번 다른 문구로 보이기 때문이다.
     * 매핑에 없는 표제는 원문을 그대로 쓴다.
     */
    private static final Map<String, String> CLAUSE_LABELS_KO = Map.ofEntries(
            Map.entry("DEFINITIONS", "정의"),
            Map.entry("VOLUME COMMITMENT", "공급 물량 약정"),
            Map.entry("PRICING AND COMMODITY INDEX ADJUSTMENT", "가격 및 원자재 지수 조정"),
            Map.entry("DELIVERY AND PENALTY", "납기 및 지연 위약금"),
            Map.entry("FORCE MAJEURE", "불가항력"),
            Map.entry("TERMINATION", "계약의 해지"),
            Map.entry("CONFIDENTIALITY", "비밀유지"),
            Map.entry("INTELLECTUAL PROPERTY", "지식재산권"),
            Map.entry("INDEMNIFICATION", "손해배상"),
            Map.entry("COMPLIANCE", "법규 준수"),
            Map.entry("RESPONSIBLE MINERAL SOURCING", "책임 광물 조달"),
            Map.entry("ENVIRONMENT & SAFETY", "환경 및 안전"),
            Map.entry("ETHICS", "윤리"),
            Map.entry("AUDIT RIGHTS", "감사권"),
            Map.entry("CUSTOMS, DUTIES, AND TARIFFS", "통관 및 관세"),
            Map.entry("SUBCONTRACTING", "하도급"),
            Map.entry("ASSIGNMENT", "양도"),
            Map.entry("DISPUTE RESOLUTION", "분쟁 해결"),
            Map.entry("GOVERNING LAW", "준거법"),
            Map.entry("NOTICES", "통지"),
            Map.entry("AMENDMENTS", "계약의 변경"),
            Map.entry("WAIVERS", "권리 포기"),
            Map.entry("SEVERABILITY", "분리 가능성"),
            Map.entry("ENTIRE AGREEMENT", "완전 합의"),
            Map.entry("INDEPENDENT CONTRACTOR", "독립 계약자"),
            Map.entry("LANGUAGE", "언어"),
            Map.entry("MISCELLANEOUS", "기타"));

    private final ContractRagRepository repository;
    private final DocumentService documentService;
    private final OutboundDocumentService outboundDocumentService;
    private final RestClient fastApiRestClient;

    public ContractRagService(
            ContractRagRepository repository,
            DocumentService documentService,
            OutboundDocumentService outboundDocumentService,
            RestClient fastApiRestClient) {
        this.repository = repository;
        this.documentService = documentService;
        this.outboundDocumentService = outboundDocumentService;
        this.fastApiRestClient = fastApiRestClient;
    }

    // ---------------------------------------------------------------- 계약 목록·상세

    /**
     * 계약 목록. 기본은 <b>ChromaDB에 실제로 적재된 계약만</b> 내려준다 — 적재 안 된 계약을
     * 골라 봤자 검색 결과가 항상 비어 화면이 고장 난 것처럼 보이기 때문이다.
     */
    public List<ContractRagDto.ContractSummary> contracts(boolean includeUnindexed) {
        // 매입(인바운드) 다음에 납품(아웃바운드)를 잇는다. 화면이 kind로 그룹을 나눠 보여주므로
        // 여기서는 두 목록을 이어 붙이기만 한다.
        List<ContractRagDto.ContractSummary> merged =
                new ArrayList<>(repository.findContracts(!includeUnindexed));
        merged.addAll(repository.findOutboundContracts(!includeUnindexed));
        return merged;
    }

    /** 우측 "계약 문서" 패널. 임베딩 표시는 가장 최근에 처리된 문서 기준이다. */
    public ContractRagDto.ContractDetail contract(long contractId) {
        ContractRagDto.ContractSummary contract = findContract(contractId);
        List<ContractRagDto.DocumentItem> documents = repository.findDocuments(contractId);

        ContractRagDto.DocumentItem latest = documents.stream()
                .filter(document -> document.embeddingType() != null)
                .findFirst()
                .orElse(null);
        String blockedReason = briefingBlockedReason(contract);

        return new ContractRagDto.ContractDetail(
                contract,
                documents,
                latest == null ? null : latest.embeddingType(),
                latest == null ? null : latest.embeddingVersion(),
                latest == null ? null : isMockEmbedding(latest.embeddingType()),
                blockedReason == null,
                blockedReason);
    }

    /**
     * 우측 "계약 문서" 패널의 아웃바운드(제품 납품) 버전. 구조는 {@link #contract}와 같되
     * 아웃바운드 테이블을 본다. AI 브리핑은 뉴스→원자재 위험 경로라 납품계약에는 해당이 없어
     * 항상 비활성으로 내려보낸다(화면이 버튼을 잠그고 사유를 보여준다).
     */
    public ContractRagDto.ContractDetail outboundContract(long outboundContractId) {
        ContractRagDto.ContractSummary contract = repository.findOutboundContract(outboundContractId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ERP_CONTRACT_NOT_FOUND));
        List<ContractRagDto.DocumentItem> documents = repository.findOutboundDocuments(outboundContractId);

        ContractRagDto.DocumentItem latest = documents.stream()
                .filter(document -> document.embeddingType() != null)
                .findFirst()
                .orElse(null);

        return new ContractRagDto.ContractDetail(
                contract,
                documents,
                latest == null ? null : latest.embeddingType(),
                latest == null ? null : latest.embeddingVersion(),
                latest == null ? null : isMockEmbedding(latest.embeddingType()),
                false,
                "납품계약은 AI 브리핑 대상이 아닙니다. 브리핑은 원자재 매입계약 기준으로 실행됩니다.");
    }

    // ---------------------------------------------------------------- 조항 검색

    /**
     * 조항 검색. {@code contractId}를 비우면 전체 계약을 훑는다.
     *
     * <p>결과마다 계약 메타를 붙이는데, 계약 조회는 <b>쿼리 한 번</b>으로 일괄 처리한다
     * (top_k만큼 계약을 낱개 조회하면 검색 한 번에 쿼리가 top_k+1번 나간다).
     */
    public ContractRagDto.SearchResponse search(ContractRagDto.SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어가 필요합니다.");
        }
        // 계약을 지정했으면 존재 확인부터 한다 — 없는 계약이면 빈 결과 대신 404가 정확하다.
        if (request.contractId() != null) {
            findContract(request.contractId());
        }

        FastApiSearchResponse response = callFastApiSearch(request);
        FastApiSearchResult result = response.data();

        // 결과를 매입/납품으로 갈라 각자의 테이블에서 메타를 채운다. **인바운드 contracts로
        // 아웃바운드를 조회하면 안 된다** — contract_id(=outbound_contract_id) 번호가 인바운드와
        // 겹쳐 엉뚱한 계약명이 붙는다. product_id 유무로 어느 쪽인지 가른다(아웃바운드 청크에만
        // product_id가 실려 온다).
        Set<Long> inboundIds = new LinkedHashSet<>();
        Set<Long> outboundIds = new LinkedHashSet<>();
        for (FastApiSearchItem item : result.results()) {
            if (item.productId() != null) {
                outboundIds.add(item.contractId());
            } else {
                inboundIds.add(item.contractId());
            }
        }
        Map<Long, ContractRagDto.ContractSummary> inboundById =
                repository.findContractsByIds(inboundIds);
        Map<Long, ContractRagDto.ContractSummary> outboundById =
                repository.findOutboundContractsByIds(outboundIds);

        List<ContractRagDto.SearchItem> items = new ArrayList<>();
        for (FastApiSearchItem item : result.results()) {
            ClauseHeading heading = ClauseHeading.parse(item.content());
            ContractRagDto.ContractSummary contract = item.productId() != null
                    ? outboundById.get(item.contractId())
                    : inboundById.get(item.contractId());
            items.add(new ContractRagDto.SearchItem(
                    item.documentId(),
                    item.chunkIndex(),
                    item.pageNumber(),
                    heading.displayTitle(),
                    heading.clauseNo(),
                    heading.rawHeading(),
                    item.similarityScore(),
                    item.content(),
                    item.contentHash(),
                    SEARCH_SOURCE,
                    contract));
        }

        return new ContractRagDto.SearchResponse(
                request.query(),
                result.scope(),
                request.contractId(),
                items.size(),
                result.mock(),
                result.embeddingType(),
                result.embeddingVersion(),
                items);
    }

    // ---------------------------------------------------------------- 업로드·재처리

    /**
     * 계약서 추가 업로드. 화면은 <b>계약과 파일만</b> 주고, 공급사·자재 ID는 계약에서 채운다
     * (기존 {@code /api/v1/documents}는 셋을 모두 요구해서 이 화면엔 맞지 않는다).
     */
    public ContractRagDto.UploadResponse upload(long contractId, MultipartFile file) {
        ContractRagDto.ContractSummary contract = findContract(contractId);
        if (contract.supplierId() == null || contract.materialId() == null) {
            throw new BusinessException(
                    ErrorCode.ERP_CONTRACT_NOT_FOUND,
                    "이 계약에는 공급사·자재가 연결돼 있지 않아 문서를 적재할 수 없습니다.");
        }

        DocumentDto.UploadResponse uploaded = documentService.upload(
                file, contractId, contract.supplierId(), contract.materialId(), UPLOAD_DOCUMENT_TYPE);
        log.info("계약 문서 업로드: contractId={}, documentId={}, chunks={}, duplicate={}",
                contractId, uploaded.documentId(), uploaded.chunkCount(), uploaded.duplicate());

        return new ContractRagDto.UploadResponse(
                uploaded.documentId(),
                uploaded.contractId(),
                uploaded.fileName(),
                uploaded.processingStatus(),
                uploaded.chunkCount(),
                uploaded.embeddingType(),
                uploaded.embeddingVersion(),
                uploaded.duplicate(),
                uploaded.mock(),
                uploaded.processedAt());
    }

    /**
     * "문서 재처리". 이 계약에 달린 문서를 전부 다시 임베딩해 ChromaDB에 올린다.
     *
     * <p>한 문서가 실패해도 나머지는 계속 처리하고 문서별 결과를 돌려준다 — 20개 중 1개가
     * 깨졌다고 전체를 실패로 되돌리면 화면에서 원인을 알 수 없다.
     */
    public ContractRagDto.ReprocessResponse reprocess(long contractId) {
        findContract(contractId);
        List<ContractRagDto.DocumentItem> documents = repository.findDocuments(contractId);

        List<ContractRagDto.ReprocessItem> items = new ArrayList<>();
        int success = 0;
        for (ContractRagDto.DocumentItem document : documents) {
            try {
                DocumentDto.UploadResponse result = documentService.reprocess(document.documentId());
                items.add(new ContractRagDto.ReprocessItem(
                        document.documentId(), document.originalFileName(),
                        true, result.chunkCount(), null, null));
                success++;
            } catch (RuntimeException exception) {
                log.warn("계약 문서 재처리 실패: contractId={}, documentId={}",
                        contractId, document.documentId(), exception);
                items.add(new ContractRagDto.ReprocessItem(
                        document.documentId(), document.originalFileName(), false, 0,
                        "DOCUMENT_REPROCESS_FAILED", exception.getMessage()));
            }
        }
        return new ContractRagDto.ReprocessResponse(
                contractId, documents.size(), success, documents.size() - success, items);
    }

    /**
     * 아웃바운드(제품 납품) 계약서 추가 업로드. {@link #upload}의 아웃바운드판 — 공급사·자재 대신
     * 제품·고객사를 계약에서 채운다. 인바운드와 동일하게 같은 내용이면 duplicate, 기존 문서가 있으면
     * document_id를 재사용해 교체한다({@link OutboundDocumentService#upload}).
     */
    public ContractRagDto.UploadResponse uploadOutbound(long outboundContractId, MultipartFile file) {
        ContractRagDto.ContractSummary contract = findOutboundContract(outboundContractId);
        if (contract.productId() == null || contract.customerId() == null) {
            throw new BusinessException(
                    ErrorCode.ERP_CONTRACT_NOT_FOUND,
                    "이 납품계약에는 제품·고객사가 연결돼 있지 않아 문서를 적재할 수 없습니다.");
        }

        OutboundDocumentDto.UploadResponse uploaded = outboundDocumentService.upload(
                file, outboundContractId, contract.productId(), contract.customerId(), UPLOAD_DOCUMENT_TYPE);
        log.info("납품계약 문서 업로드: outboundContractId={}, documentId={}, chunks={}, duplicate={}",
                outboundContractId, uploaded.documentId(), uploaded.chunkCount(), uploaded.duplicate());

        return new ContractRagDto.UploadResponse(
                uploaded.documentId(),
                uploaded.outboundContractId(),
                uploaded.fileName(),
                uploaded.processingStatus(),
                uploaded.chunkCount(),
                uploaded.embeddingType(),
                uploaded.embeddingVersion(),
                uploaded.duplicate(),
                uploaded.mock(),
                uploaded.processedAt());
    }

    /**
     * 아웃바운드 "문서 재처리". {@link #reprocess}의 아웃바운드판 — 이 계약에 달린 납품계약 문서를
     * 전부 다시 임베딩한다. 한 문서가 실패해도 나머지는 계속 처리하고 문서별 결과를 돌려준다.
     */
    public ContractRagDto.ReprocessResponse reprocessOutbound(long outboundContractId) {
        findOutboundContract(outboundContractId);
        List<ContractRagDto.DocumentItem> documents = repository.findOutboundDocuments(outboundContractId);

        List<ContractRagDto.ReprocessItem> items = new ArrayList<>();
        int success = 0;
        for (ContractRagDto.DocumentItem document : documents) {
            try {
                OutboundDocumentDto.UploadResponse result = outboundDocumentService.reprocess(document.documentId());
                items.add(new ContractRagDto.ReprocessItem(
                        document.documentId(), document.originalFileName(),
                        true, result.chunkCount(), null, null));
                success++;
            } catch (RuntimeException exception) {
                log.warn("납품계약 문서 재처리 실패: outboundContractId={}, documentId={}",
                        outboundContractId, document.documentId(), exception);
                items.add(new ContractRagDto.ReprocessItem(
                        document.documentId(), document.originalFileName(), false, 0,
                        "DOCUMENT_REPROCESS_FAILED", exception.getMessage()));
            }
        }
        return new ContractRagDto.ReprocessResponse(
                outboundContractId, documents.size(), success, documents.size() - success, items);
    }

    // ---------------------------------------------------------------- 내부 helper

    private ContractRagDto.ContractSummary findContract(long contractId) {
        return repository.findContract(contractId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ERP_CONTRACT_NOT_FOUND));
    }

    private ContractRagDto.ContractSummary findOutboundContract(long outboundContractId) {
        return repository.findOutboundContract(outboundContractId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ERP_CONTRACT_NOT_FOUND));
    }

    /**
     * 브리핑을 돌릴 수 없는 이유. 돌릴 수 있으면 null.
     *
     * <p>계약 상세와 실제 실행이 <b>같은 판정</b>을 쓴다 — 상세가 "가능"이라 해놓고 눌렀을 때
     * 422가 나면 화면이 거짓말을 한 셈이 된다. 그래서 관련 뉴스 존재 여부까지 여기서 확인한다
     * (계약 상세 조회에 쿼리 한 번이 더 붙지만, 버튼이 정확해지는 값이 더 크다).
     */
    private String briefingBlockedReason(ContractRagDto.ContractSummary contract) {
        String metadataReason = contractMetadataBlockedReason(contract);
        if (metadataReason != null) {
            return metadataReason;
        }
        return repository.findLatestRelatedNews(
                contract.materialCategory(), contract.countryCode()).isEmpty()
                ? noRelatedNewsReason(contract)
                : null;
    }

    /** 계약 자체가 갖춰지지 않아 막히는 경우. 뉴스 조회 없이 판정할 수 있는 부분이다. */
    private static String contractMetadataBlockedReason(ContractRagDto.ContractSummary contract) {
        if (contract.erpMaterialId() == null || contract.erpSupplierId() == null) {
            return "이 계약에는 ERP 자재·공급사가 연결돼 있지 않습니다.";
        }
        if (contract.materialCategory() == null || contract.materialCategory().isBlank()) {
            return "이 계약의 자재에 대분류가 지정돼 있지 않아 관련 뉴스를 찾을 수 없습니다.";
        }
        return null;
    }

    private static String noRelatedNewsReason(ContractRagDto.ContractSummary contract) {
        return "자재 " + contract.materialCategory()
                + "에 대해 분석이 끝난 뉴스가 아직 없습니다. 수집·분석이 돈 뒤에 다시 시도하세요.";
    }

    private static boolean isMockEmbedding(String embeddingType) {
        return embeddingType == null || embeddingType.toUpperCase(Locale.ROOT).startsWith("MOCK");
    }

    private FastApiSearchResponse callFastApiSearch(ContractRagDto.SearchRequest request) {
        FastApiSearchRequest upstream = new FastApiSearchRequest(
                request.query(), request.resolvedKind(), request.contractId(), request.supplierId(),
                request.materialId(), request.productId(), request.customerId(), request.resolvedTopK());
        FastApiSearchResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/contract-rag/search")
                    .body(upstream)
                    .retrieve()
                    .body(FastApiSearchResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI 계약 조항 검색 실패: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw mapFastApiError(exception);
        } catch (Exception exception) {
            log.warn("FastAPI 계약 조항 검색 연결 실패", exception);
            throw new RagSearchException(
                    "FASTAPI_UNAVAILABLE",
                    "FastAPI 검색 서버에 연결할 수 없습니다.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().results() == null) {
            throw new RagSearchException(
                    "INVALID_FASTAPI_RESPONSE",
                    "FastAPI 검색 응답이 올바르지 않습니다.",
                    HttpStatus.BAD_GATEWAY);
        }
        return response;
    }

    private static RagSearchException mapFastApiError(RestClientResponseException exception) {
        try {
            JsonNode error = ERROR_BODY_MAPPER.readTree(exception.getResponseBodyAsString())
                    .path("error");
            String code = error.path("code").asText("").trim();
            String message = error.path("message").asText("").trim();
            if (!code.isBlank()) {
                return new RagSearchException(
                        code,
                        message.isBlank() ? "FastAPI 계약 조항 검색에 실패했습니다." : message,
                        exception.getStatusCode());
            }
        } catch (Exception parseException) {
            log.debug("FastAPI 검색 오류 응답 파싱 실패", parseException);
        }
        return new RagSearchException(
                "FASTAPI_CONTRACT_SEARCH_FAILED",
                "FastAPI 계약 조항 검색에 실패했습니다.",
                exception.getStatusCode());
    }

    /**
     * 청크 본문 머리에서 뽑은 조항 번호·표제.
     *
     * <p>조항 머리를 못 찾으면(계약 표지·서문 청크 등) 본문 첫 줄을 잘라 제목으로 쓴다 —
     * 카드에 제목이 비는 것보다 낫고, 화면은 clause_no가 null인 것으로 조항 여부를 구분한다.
     */
    public record ClauseHeading(String clauseNo, String rawHeading, String displayTitle) {
        private static final int FALLBACK_TITLE_MAX = 40;

        public static ClauseHeading parse(String content) {
            String text = content == null ? "" : content.strip();
            if (text.isEmpty()) {
                return new ClauseHeading(null, null, "(내용 없음)");
            }

            Matcher korean = KO_CLAUSE.matcher(text);
            if (korean.find()) {
                String clauseNo = korean.group(1).replaceAll("\\s+", "");
                String heading = clean(korean.group(2));
                return new ClauseHeading(clauseNo, heading, join(clauseNo, heading));
            }

            Matcher english = EN_CLAUSE.matcher(text);
            if (english.find()) {
                String number = english.group(1);
                String heading = clean(english.group(2));
                String labelKo = CLAUSE_LABELS_KO.get(heading.toUpperCase(Locale.ROOT));
                return new ClauseHeading(
                        "제" + number + "조",
                        heading.isEmpty() ? null : heading,
                        join("제" + number + "조", labelKo == null ? heading : labelKo));
            }

            String firstLine = clean(text.split("\\R", 2)[0]);
            if (firstLine.length() > FALLBACK_TITLE_MAX) {
                firstLine = firstLine.substring(0, FALLBACK_TITLE_MAX).strip() + "…";
            }
            return new ClauseHeading(null, null, firstLine.isEmpty() ? "(제목 없음)" : firstLine);
        }

        private static String clean(String value) {
            return value == null ? "" : value.replaceAll("[\\s.:()\\-]+$", "").strip();
        }

        private static String join(String clauseNo, String heading) {
            return heading == null || heading.isEmpty() ? clauseNo : clauseNo + " · " + heading;
        }
    }

    // FastAPI /api/v1/contract-rag/search 와 주고받는 형태. 화면 응답과 분리해 둔다.
    private record FastApiSearchRequest(
            String query,
            String kind,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
            @JsonProperty("product_id") Long productId,
            @JsonProperty("customer_id") Long customerId,
            @JsonProperty("top_k") int topK) {}

    private record FastApiSearchResponse(boolean success, FastApiSearchResult data) {}

    private record FastApiSearchResult(
            List<FastApiSearchItem> results,
            String scope,
            boolean mock,
            @JsonProperty("embedding_type") String embeddingType,
            @JsonProperty("embedding_version") String embeddingVersion,
            @JsonProperty("collection_name") String collectionName) {}

    private record FastApiSearchItem(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("chunk_index") int chunkIndex,
            @JsonProperty("page_number") int pageNumber,
            String content,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("similarity_score") double similarityScore,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
            // product_id가 실려 오면 이 청크는 아웃바운드(납품) 계약이다 — 메타 보강 테이블을 가른다.
            @JsonProperty("product_id") Long productId,
            @JsonProperty("customer_id") Long customerId) {}
}

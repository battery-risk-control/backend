package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.dto.MultiAgentDto;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1계층 구매팀 "계약 · RAG 검색" 화면의 조항 검색 · 계약 문서 조회 · 업로드 · 재처리 ·
 * "이 근거로 AI 브리핑 생성"을 담당한다.
 *
 * <p><b>기존 RAG · 멀티에이전트 코드는 건드리지 않는다.</b> 검색은 FastAPI에 새로 만든
 * {@code /api/v1/contract-rag/search}를 부르고(필터 없이 전체 계약 검색이 가능한 경로),
 * 적재·재처리는 {@link DocumentService}를, 브리핑은 {@link MultiAgentOrchestrationService}를
 * <b>있는 그대로 호출만</b> 한다.
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
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;
    private final RestClient fastApiRestClient;

    public ContractRagService(
            ContractRagRepository repository,
            DocumentService documentService,
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            RestClient fastApiRestClient) {
        this.repository = repository;
        this.documentService = documentService;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.fastApiRestClient = fastApiRestClient;
    }

    // ---------------------------------------------------------------- 계약 목록·상세

    /**
     * 계약 목록. 기본은 <b>ChromaDB에 실제로 적재된 계약만</b> 내려준다 — 적재 안 된 계약을
     * 골라 봤자 검색 결과가 항상 비어 화면이 고장 난 것처럼 보이기 때문이다.
     */
    public List<ContractRagDto.ContractSummary> contracts(boolean includeUnindexed) {
        return repository.findContracts(!includeUnindexed);
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

        Set<Long> contractIds = new LinkedHashSet<>();
        for (FastApiSearchItem item : result.results()) {
            contractIds.add(item.contractId());
        }
        Map<Long, ContractRagDto.ContractSummary> contractsById =
                repository.findContractsByIds(contractIds);

        List<ContractRagDto.SearchItem> items = new ArrayList<>();
        for (FastApiSearchItem item : result.results()) {
            ClauseHeading heading = ClauseHeading.parse(item.content());
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
                    contractsById.get(item.contractId())));
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

    // ---------------------------------------------------------------- AI 브리핑

    /**
     * "이 근거로 AI 브리핑 생성".
     *
     * <p>흐름: 계약 → 자재 대분류·공급사 국가 → <b>DB에 저장된 가장 최신 관련 뉴스 분석</b> →
     * 멀티에이전트 실행 → 결과 반환. 뉴스를 새로 수집하거나 분석하지 않는다 — 이미 쌓여 있는
     * 분석 결과를 계약 관점에서 다시 읽는 화면이기 때문이다.
     *
     * <p>외부신호는 {@code analysisId} 경로로 넘긴다. 그래야 화면이 보여준 뉴스의 severity가
     * 그대로 종합 점수의 입력이 되어 두 숫자가 어긋나지 않는다
     * ({@code RiskMonitoringService.runErpImpact}와 같은 방식).
     *
     * <p><b>알려진 한계</b> — 멀티에이전트가 RAG로 뒤질 계약은 요청이 아니라 ERP 컨텍스트가
     * 정한다(그래프·오케스트레이션 코드를 건드리지 않기로 했으므로 그대로 둔다). 그래서 고른
     * 계약과 실제 검색된 계약이 다를 수 있다. 화면이 알 수 있도록 요청한 계약과 선택한 근거를
     * 응답에 그대로 실어 보낸다.
     */
    public ContractRagDto.BriefingResponse briefing(ContractRagDto.BriefingRequest request) {
        if (request == null || request.contractId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "contract_id가 필요합니다.");
        }
        ContractRagDto.ContractSummary contract = findContract(request.contractId());

        // 계약 메타 판정과 뉴스 조회를 나눠 부른다 — briefingBlockedReason을 그대로 쓰면
        // 같은 뉴스 쿼리가 두 번 나간다(판정 한 번, 실제 사용 한 번).
        String metadataReason = contractMetadataBlockedReason(contract);
        if (metadataReason != null) {
            throw new BusinessException(ErrorCode.MATERIAL_BRIEFING_NOT_AVAILABLE, metadataReason);
        }

        ContractRagDto.SourceNews news = repository
                .findLatestRelatedNews(contract.materialCategory(), contract.countryCode())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MATERIAL_BRIEFING_NOT_AVAILABLE, noRelatedNewsReason(contract)));

        MultiAgentDto.GenerateRequest generateRequest = new MultiAgentDto.GenerateRequest(
                news.analysisId().toString(),
                news.analysisId(),
                news.title(),
                news.summaryKr() == null ? "" : news.summaryKr(),
                news.summaryKr() == null ? "" : news.summaryKr(),
                news.impactDomain(),
                news.impactDomain(),
                null, null,
                contract.erpMaterialId(),
                contract.erpSupplierId(),
                news.countryCode(),
                OffsetDateTime.now(),
                request.useLlm());

        MultiAgentDto.Response response = multiAgentOrchestrationService.generate(generateRequest);
        log.info("계약 기반 AI 브리핑 생성: contractId={}, analysisId={}, level={}, evidence={}",
                contract.contractId(), news.analysisId(), response.procurementRiskLevel(),
                request.evidence() == null ? 0 : request.evidence().size());

        return new ContractRagDto.BriefingResponse(
                response.assessmentId(),
                contract,
                news,
                isComposite(response),
                response.procurementRiskLevel(),
                response.procurementRiskScore(),
                response.riskReasons() == null ? List.of() : response.riskReasons(),
                response.briefing(),
                response.recommendedActions() == null ? List.of() : response.recommendedActions(),
                response.contractFindings() == null ? List.of() : response.contractFindings(),
                request.evidence() == null ? List.of() : request.evidence(),
                response.llmUsed(),
                response.reviewPassed(),
                response.warnings() == null ? List.of() : response.warnings());
    }

    // ---------------------------------------------------------------- 내부 helper

    private ContractRagDto.ContractSummary findContract(long contractId) {
        return repository.findContract(contractId)
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

    /**
     * ERP·계약 노드까지 실제로 돈 실행인지.
     *
     * <p>LangGraph는 KG 게이트에서 매칭이 없으면 ERP·계약 노드를 건너뛰고 조기 종료하는데,
     * 그때도 등급 NORMAL · 0점으로 응답이 나온다. 그 0을 종합 판정으로 읽으면 안 되므로
     * ERP 노출도 점수의 존재로 구분한다 — 조기 종료 경로는 erp_assessment가 비어 있다.
     * ({@code RiskMonitoringService.isComposite}와 같은 판별 기준)
     */
    private static boolean isComposite(MultiAgentDto.Response response) {
        Map<String, Object> erpAssessment = response.erpAssessment();
        return erpAssessment != null && erpAssessment.get("erp_exposure_score") instanceof Number;
    }

    private static boolean isMockEmbedding(String embeddingType) {
        return embeddingType == null || embeddingType.toUpperCase(Locale.ROOT).startsWith("MOCK");
    }

    private FastApiSearchResponse callFastApiSearch(ContractRagDto.SearchRequest request) {
        FastApiSearchRequest upstream = new FastApiSearchRequest(
                request.query(), request.contractId(), request.supplierId(),
                request.materialId(), request.resolvedTopK());
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
            @JsonProperty("contract_id") Long contractId,
            @JsonProperty("supplier_id") Long supplierId,
            @JsonProperty("material_id") Long materialId,
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
            @JsonProperty("similarity_score") double similarityScore) {}
}

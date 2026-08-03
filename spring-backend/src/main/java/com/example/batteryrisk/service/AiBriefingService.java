package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.AiBriefingDto;
import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.RiskMonitoringDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ContractRagRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 1계층 구매팀 "AI 브리핑" 화면 서비스.
 *
 * <p>앞선 세 화면(리스크 모니터링 · 원자재 위험 · 계약·RAG)에서 넘어온 대상을 받아
 * <b>기존 멀티에이전트를 그대로 한 번 돌리고</b>, 그 결과를 화면 칸에 맞춰 펼쳐 저장한다.
 * 새로 계산하는 값은 없다 — 점수·문구·근거는 전부 {@link MultiAgentOrchestrationService}의 응답이다.
 *
 * <p><b>세 화면의 판정을 그대로 빌려 쓴다.</b> "지금 브리핑을 돌릴 수 있는가"를 여기서 다시
 * 판단하면 앞 화면이 버튼을 활성화해 놓고 눌렀을 때 422가 나는 상황이 생긴다. 그래서
 * {@code RiskMonitoringService.detail().erpImpactBlockedReason()},
 * {@code MaterialRiskService.detail().briefingBlockedReason()},
 * {@code ContractRagService.contract().briefingBlockedReason()}을 그대로 읽어 쓴다.
 *
 * <p>ERP 연결(자재 · 공급사 · 계약)과 공급사 의존도는 {@link ErpService#buildContext}에서 온다.
 * ERP Exposure Agent가 의존도를 응답에 되돌려 주지 않아, 화면의 "공급사 의존도 84%"를 채울 곳이
 * 거기밖에 없다.
 */
@Service
public class AiBriefingService {
    private static final Logger log = LoggerFactory.getLogger(AiBriefingService.class);

    private static final String SOURCE_NEWS = "NEWS";
    private static final String SOURCE_MATERIAL = "MATERIAL";
    private static final String SOURCE_CONTRACT = "CONTRACT";

    /** 최근 브리핑 기본 노출 건수. 화면 우측 카드가 한 번에 보여주는 양이다. */
    private static final int DEFAULT_RECENT_LIMIT = 5;

    /**
     * 같은 대상·같은 분석의 재요청을 "재시도"로 볼 시간창(초).
     *
     * <p>브리핑 한 건은 LLM 호출을 태우므로 응답이 유실돼 사용자가 다시 누르면 비용이 두 번 나간다.
     * 화면은 실행 중 버튼을 잠그지만 그것만으로는 네트워크 실패 후 재시도를 막을 수 없다.
     *
     * <p>60초인 이유는 실행이 보통 10~20초라 재시도가 그 안에 들어오고, 결과를 읽어 본 뒤
     * "다시 돌려보자"는 <b>의도적인</b> 재생성은 그보다 오래 걸리기 때문이다.
     */
    private static final long DUPLICATE_WINDOW_SECONDS = 60;

    /**
     * 저장에 남길 가중치 조합 버전. {@link MultiAgentOrchestrationService}가 쓰는 값과 같아야 한다 —
     * 같은 실행 결과를 두 테이블에 남기는데 버전이 어긋나면 과거 점수를 해석할 수 없다.
     */
    private static final String WEIGHT_VERSION = "procurement-risk-v1";

    /**
     * 브리핑을 누가 만들었는지(V29). 자동 생성분과 화면 생성분이 같은 목록에 쌓이므로 구분이 필요하다.
     * 값은 {@code ck_ai_briefing_trigger} 제약과 일치해야 한다.
     */
    private static final String TRIGGER_AUTO = "AUTO";
    private static final String TRIGGER_MANUAL = "MANUAL";

    private final RiskMonitoringService riskMonitoringService;
    private final MaterialRiskService materialRiskService;
    private final ContractRagService contractRagService;
    private final ContractRagRepository contractRagRepository;
    private final RawEventRepository rawEventRepository;
    private final AnalysisRepository analysisRepository;
    private final ErpExposureRequestService erpExposureRequestService;
    private final ErpService erpService;
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;
    private final AiBriefingRepository repository;

    public AiBriefingService(
            RiskMonitoringService riskMonitoringService,
            MaterialRiskService materialRiskService,
            ContractRagService contractRagService,
            ContractRagRepository contractRagRepository,
            RawEventRepository rawEventRepository,
            AnalysisRepository analysisRepository,
            ErpExposureRequestService erpExposureRequestService,
            ErpService erpService,
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            AiBriefingRepository repository) {
        this.riskMonitoringService = riskMonitoringService;
        this.materialRiskService = materialRiskService;
        this.contractRagService = contractRagService;
        this.contractRagRepository = contractRagRepository;
        this.rawEventRepository = rawEventRepository;
        this.analysisRepository = analysisRepository;
        this.erpExposureRequestService = erpExposureRequestService;
        this.erpService = erpService;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.repository = repository;
    }

    // ---------------------------------------------------------------- 프리필

    /**
     * 화면 상단 "분석 대상 · ERP 연결"을 채운다. <b>멀티에이전트를 돌리지 않는다</b> —
     * 여기서 돌리면 화면에 들어오기만 해도 LLM 비용이 나간다.
     */
    public AiBriefingDto.Context context(String source, String ref) {
        ResolvedSource resolved = resolveSource(source, ref);
        ErpDto.ContextResponse erp = tryBuildErpContext(resolved);

        String blockedReason = resolved.blockedReason() != null
                ? resolved.blockedReason()
                : (erp == null ? "ERP 재고 정보를 구성할 수 없어 브리핑을 생성할 수 없습니다." : null);

        Analysis analysis = resolved.analysis();
        return new AiBriefingDto.Context(
                resolved.sourceType(),
                resolved.sourceRef(),
                resolved.subjectTitle(),
                analysis == null ? null : analysis.getAnalysisId().toString(),
                analysis == null ? null : analysis.getAnalysisId(),
                analysis == null ? null : analysis.getEventTitle(),
                erp != null ? erp.erpMaterialId() : resolved.erpMaterialId(),
                erp != null ? erp.erpSupplierId() : resolved.erpSupplierId(),
                erp == null ? null : erp.erpContractId(),
                erp != null && erp.primaryContractId() != null
                        ? erp.primaryContractId()
                        : resolved.contractId(),
                erp == null ? resolved.materialName() : erp.materialName(),
                resolved.materialCategory(),
                analysis == null ? null : analysis.getCountryCode(),
                analysis == null ? null : analysis.getImpactDomain(),
                analysis == null ? null : upperOrNull(analysis.getSeverity()),
                analysis == null || analysis.getSeverityScore() == null
                        ? null
                        : BigDecimal.valueOf(analysis.getSeverityScore()),
                blockedReason == null,
                blockedReason,
                // 앞 화면에서 넘어오면 프리필만 되고 본문은 비어 있었다. 이미 만들어 둔 브리핑이
                // 있는데도 화면이 그 사실을 몰라 "생성"을 다시 눌러야 했고, 그때마다 LLM 비용이
                // 또 나갔다. 대상 기준으로 최신 1건을 알려주면 화면이 바로 본문을 채운다.
                repository.findLatestBriefingId(
                                resolved.sourceType(), resolved.sourceRef(),
                                analysis == null ? null : analysis.getAnalysisId())
                        .orElse(null));
    }

    // ---------------------------------------------------------------- 생성

    /**
     * "LLM 브리핑 생성" — 멀티에이전트를 실행하고 결과를 {@code ai_briefings}에 남긴다.
     *
     * <p>저장 실패를 삼키지 않는다. 브리핑 한 건은 LangGraph 노드와 LLM 호출을 태운 비싼 결과라,
     * 조용히 저장을 건너뛰면 "최근 브리핑에 안 뜬다"는 형태로만 드러난다
     * ({@code MultiAgentOrchestrationService.persist}와 같은 방침).
     */
    public AiBriefingDto.BriefingDetail generate(AiBriefingDto.GenerateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 본문이 필요합니다.");
        }
        ResolvedSource resolved = resolveSource(request.source(), request.ref());
        if (resolved.blockedReason() != null) {
            throw new BusinessException(
                    ErrorCode.MATERIAL_BRIEFING_NOT_AVAILABLE, resolved.blockedReason());
        }

        Analysis analysis = pinAnalysis(resolved, request.analysisId());
        OffsetDateTime asOf = OffsetDateTime.now();

        // 재시도로 인한 중복 실행 차단. 그래프를 태우기 <b>전</b>에 본다 — 저장 직전에 보면
        // 이미 LLM 비용이 나간 뒤라 막는 의미가 없다.
        Optional<AiBriefingDto.BriefingDetail> duplicate = repository.findRecentDuplicate(
                resolved.sourceType(), resolved.sourceRef(), analysis.getAnalysisId(),
                asOf.minusSeconds(DUPLICATE_WINDOW_SECONDS));
        if (duplicate.isPresent()) {
            log.info("AI 브리핑 중복 요청 — 방금 만든 브리핑을 그대로 반환: source={}/{}, briefingId={}",
                    resolved.sourceType(), resolved.sourceRef(), duplicate.get().briefingId());
            return duplicate.get();
        }

        ErpDto.ContextResponse erp = erpService.buildContext(new ErpDto.ContextRequest(
                resolved.erpMaterialId(), resolved.erpSupplierId(), asOf));

        // 버튼 이름이 "LLM 브리핑 생성"이라 기본이 true다. 다른 화면들은 기본 false인 점에 주의.
        boolean useLlm = request.useLlm() == null || request.useLlm();

        // analysisId를 실어 보내면 외부신호(가중치 0.35)를 analyses.severity_score에서 읽는다 —
        // 화면이 이미 보여준 외부신호 점수와 종합 점수의 입력이 어긋나지 않는 유일한 경로다
        // (RiskMonitoringService.runErpImpact와 같은 조립).
        MultiAgentDto.Response response = multiAgentOrchestrationService.generate(
                new MultiAgentDto.GenerateRequest(
                        analysis.getAnalysisId().toString(),
                        analysis.getAnalysisId(),
                        analysis.getEventTitle(),
                        analysis.getEventContent() == null ? "" : analysis.getEventContent(),
                        analysis.getSummaryKr() == null ? "" : analysis.getSummaryKr(),
                        analysis.getImpactDomain(),
                        analysis.getImpactDomain(),
                        null, null,
                        erp.erpMaterialId(),
                        erp.erpSupplierId(),
                        analysis.getCountryCode(),
                        asOf,
                        useLlm));

        AiBriefingDto.BriefingDetail detail = toDetail(resolved, erp, analysis, response, asOf, TRIGGER_MANUAL);
        repository.save(detail, currentUsername());

        log.info("AI 브리핑 생성: source={}/{}, analysisId={}, level={}, composite={}, llmUsed={}",
                resolved.sourceType(), resolved.sourceRef(), analysis.getAnalysisId(),
                detail.procurementRiskLevel(), detail.composite(), response.llmUsed());
        return detail;
    }

    /**
     * 자동 분석 경로(Chain A&rarr;B)가 이미 돌린 멀티에이전트 결과를 {@code ai_briefings}에 남긴다.
     *
     * <p>V29 전까지 자동 경로의 브리핑 본문은 {@code procurement_risk_assessments}에만 들어가서
     * AI 브리핑 화면 목록에 끝내 나오지 않았다(고아 8건). 본문 정본을 {@code ai_briefings}로
     * 통일하면서, 자동 생성분도 같은 목록에 {@code trigger_type='AUTO'}로 쌓이게 한다.
     *
     * <p>{@link #generate}와 달리 그래프를 다시 돌리지 않는다 — 호출자가 이미 받은 응답을 넘긴다.
     * 여기서 재실행하면 같은 뉴스에 LLM 비용이 두 번 나간다.
     *
     * <p>{@code created_by}는 비운다. 사람이 버튼을 누른 게 아니므로 화면이 작성자 칸을 비워야
     * 하고, 그 자체가 자동 생성분을 알아보는 표식이 된다(V29 백필과 같은 규칙).
     */
    public void recordAutoGenerated(
            Analysis analysis, String erpMaterialId, String erpSupplierId,
            String materialCategory, MultiAgentDto.Response response) {
        OffsetDateTime asOf = OffsetDateTime.now();
        ErpDto.ContextResponse erp = erpService.buildContext(
                new ErpDto.ContextRequest(erpMaterialId, erpSupplierId, asOf));

        // 자동 경로는 언제나 뉴스 이벤트에서 출발한다. 자재·계약 화면에서 시작하는 브리핑은
        // generate()를 타므로 여기 오지 않는다.
        ResolvedSource resolved = new ResolvedSource(
                "NEWS", analysis.getAnalysisId().toString(), analysis.getEventTitle(),
                erp.materialName(), materialCategory, erpMaterialId, erpSupplierId,
                erp.primaryContractId(), analysis, null);

        AiBriefingDto.BriefingDetail detail =
                toDetail(resolved, erp, analysis, response, asOf, TRIGGER_AUTO);
        repository.save(detail, null);

        log.info("AI 브리핑 자동 저장: analysisId={}, level={}, composite={}, llmUsed={}",
                analysis.getAnalysisId(), detail.procurementRiskLevel(),
                detail.composite(), response.llmUsed());
    }

    // ---------------------------------------------------------------- 조회

    /** 우측 "최근 브리핑". 팀 전체 공용이라 만든 사람으로 거르지 않는다. */
    public List<AiBriefingDto.BriefingListItem> recent(Integer limit) {
        int size = limit == null || limit < 1 ? DEFAULT_RECENT_LIMIT : Math.min(limit, 50);
        return repository.findRecent(size);
    }

    /** "브리핑 상세 보기" — 저장된 브리핑을 생성 직후와 같은 모양으로 되돌린다. */
    public AiBriefingDto.BriefingDetail get(UUID briefingId) {
        return repository.findById(briefingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 소스 해석

    /**
     * 앞 화면에서 넘어온 대상 하나를 멀티에이전트 입력으로 정규화한 것.
     *
     * <p>{@code blockedReason}이 채워져 있으면 지금은 브리핑을 돌릴 수 없다는 뜻이고, 그 문구는
     * 앞 화면이 버튼 옆에 띄우던 것과 <b>같은 문장</b>이다.
     */
    record ResolvedSource(
            String sourceType,
            String sourceRef,
            String subjectTitle,
            String materialName,
            String materialCategory,
            String erpMaterialId,
            String erpSupplierId,
            Long contractId,
            Analysis analysis,
            String blockedReason) {}

    ResolvedSource resolveSource(String source, String ref) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (ref == null || ref.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "ref가 필요합니다.");
        }
        return switch (normalized) {
            case SOURCE_NEWS -> resolveNews(ref.trim());
            case SOURCE_MATERIAL -> resolveMaterial(ref.trim());
            case SOURCE_CONTRACT -> resolveContract(ref.trim());
            default -> throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "source는 NEWS · MATERIAL · CONTRACT 중 하나여야 합니다.");
        };
    }

    /**
     * 뉴스 ref를 {@code raw_events.id}로 정규화한다. 숫자면 그대로 쓰고, 분석 UUID면 그 분석을
     * 낳은 수집 이벤트를 되찾는다.
     *
     * <p>UUID를 받아야 하는 이유: 자동 경로(Chain A&rarr;B)가 만든 브리핑은 {@code source_ref}에
     * 분석 UUID가 들어 있다. 그 시점엔 아직 {@code raw_events.triggered_analysis_id}가 채워지기
     * 전이라({@code CollectionService.triggerTestNews}가 분석을 돌린 <b>뒤에</b> 표시한다)
     * eventId를 알 수 없기 때문이다. 반면 지금은 이미 채워져 있으므로 여기서 되찾을 수 있다.
     * 이게 없으면 화면이 자동 생성 브리핑을 열었을 때 "분석 대상"과 "ERP 연결"이 비어 보인다.
     */
    private long resolveNewsEventId(String ref) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException notANumber) {
            UUID analysisId;
            try {
                analysisId = UUID.fromString(ref);
            } catch (IllegalArgumentException notAUuid) {
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST, "ref는 eventId(숫자) 또는 분석 UUID여야 합니다.");
            }
            return rawEventRepository.findFirstByTriggeredAnalysisId(analysisId)
                    .map(RawEvent::getId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RISK_EVENT_NOT_FOUND));
        }
    }

    /**
     * 리스크 모니터링에서 넘어온 뉴스 1건.
     *
     * <p>실행 가능 판정은 {@link RiskMonitoringService#detail}이 이미 내려 준 것을 그대로 쓰고,
     * 멀티에이전트에 넘길 ERP 자재·공급사만 {@link ErpExposureRequestService#resolveErpTarget}으로
     * 찾는다(KG가 확정한 공급사를 우선 쓰는 그 경로다).
     */
    private ResolvedSource resolveNews(String ref) {
        long eventId = resolveNewsEventId(ref);
        RiskMonitoringDto.EventDetail detail = riskMonitoringService.detail(eventId);

        RawEvent event = rawEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RISK_EVENT_NOT_FOUND));
        Analysis analysis = event.getTriggeredAnalysisId() == null
                ? null
                : analysisRepository.findById(event.getTriggeredAnalysisId()).orElse(null);

        String subject = detail.headline() != null ? detail.headline() : event.getTitle();
        if (!detail.erpImpactAvailable() || analysis == null) {
            return blocked(SOURCE_NEWS, ref, subject, analysis,
                    detail.erpImpactBlockedReason() != null
                            ? detail.erpImpactBlockedReason()
                            : "아직 AI 분석(F3)이 실행되지 않은 기사입니다.");
        }

        String materialCategory = analysis.getMaterialCategory() != null
                && !analysis.getMaterialCategory().isBlank()
                ? analysis.getMaterialCategory()
                : RiskEventService.guessMaterialCategory(event.getTitle(), event.getContent());
        if (materialCategory == null) {
            return blocked(SOURCE_NEWS, ref, subject, analysis,
                    "기사에서 영향 원자재를 특정하지 못했습니다.");
        }

        ErpExposureRequestService.ResolvedErpTarget target =
                erpExposureRequestService.resolveErpTarget(analysis.getCountryCode(), materialCategory);
        if (target == null) {
            return blocked(SOURCE_NEWS, ref, subject, analysis,
                    "자재 대분류 " + materialCategory + "에 연결된 ERP 자재·주 공급사가 없습니다.");
        }

        return new ResolvedSource(SOURCE_NEWS, ref, subject, null, materialCategory,
                target.erpMaterialId(), target.erpSupplierId(), null, analysis, null);
    }

    /**
     * 원자재 위험에서 넘어온 자재 1건.
     *
     * <p>이 화면에는 뉴스가 없으므로 외부신호는 저장된 같은 대분류의 최신 분석에서 끌어온다
     * ({@code MaterialRiskService.briefing}과 같은 방침). 그 조회는
     * {@link ContractRagRepository#findLatestRelatedNews}가 이미 갖고 있어 그대로 쓴다 —
     * "멀티에이전트를 태울 수 있는 분석"의 조건(완료·점수 존재·국가 특정·공급망 무관 아님)이
     * SQL에 들어 있다.
     */
    private ResolvedSource resolveMaterial(String ref) {
        MaterialRiskDto.MaterialDetail detail = materialRiskService.detail(ref);
        String subject = detail.materialName() != null ? detail.materialName() : ref;

        if (!detail.briefingAvailable()) {
            return blocked(SOURCE_MATERIAL, ref, subject, null, detail.briefingBlockedReason());
        }

        Optional<Analysis> analysis = findExternalSignalAnalysis(detail.materialCategory(), null);
        if (analysis.isEmpty()) {
            return blocked(SOURCE_MATERIAL, ref, subject, null,
                    "저장된 " + detail.materialCategory() + " 관련 뉴스 분석이 없어 외부신호를 만들 수 없습니다.");
        }

        return new ResolvedSource(SOURCE_MATERIAL, ref, subject,
                detail.materialName(), detail.materialCategory(),
                detail.erpMaterialId(),
                detail.primarySupplier() == null ? null : detail.primarySupplier().erpSupplierId(),
                detail.linkedContract() == null ? null : detail.linkedContract().contractId(),
                analysis.get(), null);
    }

    /**
     * 계약·RAG에서 넘어온 계약 1건.
     *
     * <p>외부신호는 이 계약의 자재 대분류와 공급사 국적에 맞는 최신 분석에서 끌어온다
     * ({@code ContractRagService.briefing}과 같은 방침).
     */
    private ResolvedSource resolveContract(String ref) {
        long contractId = parseLong(ref, "contractId");
        ContractRagDto.ContractDetail detail = contractRagService.contract(contractId);
        ContractRagDto.ContractSummary contract = detail.contract();
        String subject = contract.contractName() != null
                ? contract.contractName()
                : contract.erpContractId();

        if (!detail.briefingAvailable()) {
            return blocked(SOURCE_CONTRACT, ref, subject, null, detail.briefingBlockedReason());
        }

        Optional<Analysis> analysis =
                findExternalSignalAnalysis(contract.materialCategory(), contract.countryCode());
        if (analysis.isEmpty()) {
            return blocked(SOURCE_CONTRACT, ref, subject, null,
                    "자재 " + contract.materialCategory() + "에 대해 분석이 끝난 뉴스가 아직 없습니다.");
        }

        return new ResolvedSource(SOURCE_CONTRACT, ref, subject,
                contract.materialName(), contract.materialCategory(),
                contract.erpMaterialId(), contract.erpSupplierId(), contract.contractId(),
                analysis.get(), null);
    }

    /**
     * 프리필이 보여준 분석으로 고정한다. 지정이 없으면 방금 해석한 것을 그대로 쓴다.
     *
     * <p>자재·계약 경로는 "같은 대분류의 가장 최신 분석"을 고르므로, 프리필과 생성 사이에 수집
     * 스케줄러가 새 분석을 넣으면 <b>화면이 보여준 외부신호와 다른 뉴스로</b> 그래프가 돈다.
     * 화면이 프리필의 analysis_id를 그대로 실어 보내면 그 창이 닫힌다.
     *
     * <p>아무 분석이나 받아주지는 않는다 — 대상과 무관한 id를 넣어 다른 뉴스로 실행하는 우회로가
     * 되면 저장된 브리핑의 계보를 믿을 수 없게 된다. 뉴스 경로는 기사에 묶인 분석이 유일하므로
     * 정확히 일치해야 하고, 자재·계약 경로는 같은 자재 대분류여야 한다.
     *
     * <p>고정한 분석이 실제로 쓸 수 있는지(완료·점수 존재·공급망 관련)는 여기서 보지 않는다 —
     * {@link MultiAgentOrchestrationService}가 같은 조건을 이미 검사하고 거절한다.
     */
    private Analysis pinAnalysis(ResolvedSource resolved, UUID requestedAnalysisId) {
        Analysis resolvedAnalysis = resolved.analysis();
        if (requestedAnalysisId == null
                || requestedAnalysisId.equals(resolvedAnalysis.getAnalysisId())) {
            return resolvedAnalysis;
        }
        Analysis pinned = analysisRepository.findById(requestedAnalysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));

        boolean sameTarget = !SOURCE_NEWS.equals(resolved.sourceType())
                && resolved.materialCategory() != null
                && resolved.materialCategory().equals(pinned.getMaterialCategory());
        if (!sameTarget) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "analysis_id가 이 대상의 분석과 일치하지 않습니다. 프리필(GET /context)이 "
                            + "돌려준 값을 그대로 보내세요.");
        }
        log.info("AI 브리핑 외부신호 고정: source={}/{}, 프리필={}, 해석={}",
                resolved.sourceType(), resolved.sourceRef(),
                requestedAnalysisId, resolvedAnalysis.getAnalysisId());
        return pinned;
    }

    /**
     * 외부신호로 쓸 분석 1건. 조건 판정은 {@link ContractRagRepository#findLatestRelatedNews}에
     * 맡기고, 멀티에이전트 입력에 필요한 본문({@code event_content})을 위해 엔티티로 다시 읽는다.
     */
    private Optional<Analysis> findExternalSignalAnalysis(String materialCategory, String countryCode) {
        return contractRagRepository.findLatestRelatedNews(materialCategory, countryCode)
                .map(ContractRagDto.SourceNews::analysisId)
                .flatMap(analysisRepository::findById);
    }

    private static ResolvedSource blocked(
            String sourceType, String ref, String subject, Analysis analysis, String reason) {
        return new ResolvedSource(sourceType, ref, subject, null, null, null, null, null, analysis,
                reason == null ? "지금은 이 대상으로 브리핑을 생성할 수 없습니다." : reason);
    }

    /** 프리필용 ERP Context. 실패해도 화면은 떠야 하므로 예외 대신 null을 돌려준다. */
    private ErpDto.ContextResponse tryBuildErpContext(ResolvedSource resolved) {
        if (resolved.erpMaterialId() == null) {
            return null;
        }
        try {
            return erpService.buildContext(new ErpDto.ContextRequest(
                    resolved.erpMaterialId(), resolved.erpSupplierId(), OffsetDateTime.now()));
        } catch (BusinessException exception) {
            log.info("AI 브리핑 프리필 ERP Context 구성 실패: material={}, reason={}",
                    resolved.erpMaterialId(), exception.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 응답 조립

    /**
     * 멀티에이전트 응답을 화면 칸으로 펼친다.
     *
     * <p>세부 점수는 응답 Map 안에 snake_case 키로 들어 있다 — FastAPI 모델의 alias_generator는
     * <b>필드 이름만</b> camelCase로 바꾸고 dict 내용은 건드리지 않는다
     * ({@code MultiAgentOrchestrationService.numeric}과 같은 이유).
     */
    private AiBriefingDto.BriefingDetail toDetail(
            ResolvedSource resolved,
            ErpDto.ContextResponse erp,
            Analysis analysis,
            MultiAgentDto.Response response,
            OffsetDateTime asOf,
            String triggerType) {
        Map<String, Object> erpAssessment = response.erpAssessment();

        // ERP 노출도 점수의 존재로 조기 종료를 판별한다. 조기 종료 경로는 erp_assessment를 빈 dict로
        // 두므로 이 값이 반드시 없다(RiskMonitoringService·ContractRagService의 isComposite와 같은 기준).
        BigDecimal erpExposureScore = numeric(erpAssessment, "erp_exposure_score");
        boolean composite = erpExposureScore != null;

        List<Map<String, Object>> findings = nullToEmpty(response.contractFindings());
        BigDecimal contractGapScore = numeric(response.contractAssessment(), "contract_gap_score");

        // 점수 4개는 NUMERIC(5,1) 컬럼에 들어간다. 여기서 자릿수를 맞춰 두지 않으면 생성 직후 응답은
        // 36, 저장 후 다시 읽은 응답은 36.0이 되어 같은 브리핑이 두 모양으로 나간다.

        AiBriefingDto.ErpEvidence evidence = new AiBriefingDto.ErpEvidence(
                scale1(erpExposureScore),
                upperOrNull(text(erpAssessment, "exposure_level")),
                firstNonNull(numeric(erpAssessment, "inventory_days"), erp.inventoryDays()),
                firstNonNull(numeric(erpAssessment, "safety_stock_days"), erp.safetyStockDays()),
                firstNonNull(integer(erpAssessment, "next_inbound_eta_days"), erp.nextEtaDays()),
                firstNonNull(
                        firstNonNull(
                                numeric(erpAssessment, "expected_supply_gap_days"),
                                numeric(erpAssessment, "projected_supply_gap_days")),
                        erp.expectedSupplyGapDays()),
                erp.supplierDependencyRatio(),
                flag(erpAssessment, "stockout_before_eta"));

        BigDecimal externalScore = analysis.getSeverityScore() == null
                ? null
                : scale1(BigDecimal.valueOf(analysis.getSeverityScore()));
        BigDecimal finalScore = scale1(BigDecimal.valueOf(response.procurementRiskScore()));
        String finalLevel = upperOrNull(response.procurementRiskLevel());

        AiBriefingDto.EvidenceChain chain = new AiBriefingDto.EvidenceChain(
                new AiBriefingDto.Step("외부 이벤트", upperOrNull(analysis.getSeverity()), externalScore, null),
                new AiBriefingDto.Step("ERP 노출", evidence.exposureLevel(), evidence.exposureScore(), null),
                new AiBriefingDto.Step("계약 RAG", null, scale1(contractGapScore), findings.size() + "개 조항"),
                new AiBriefingDto.Step("최종 위험", finalLevel, finalScore, null));

        List<String> warnings = nullToEmpty(response.warnings());
        AiBriefingDto.VerificationMeta verification = new AiBriefingDto.VerificationMeta(
                response.reviewPassed(),
                response.llmUsed(),
                response.llmError(),
                warnings.size(),
                warnings,
                firstLong(findings, "contract_id"),
                firstInteger(findings, "page"),
                WEIGHT_VERSION,
                analysis.isMock());

        return new AiBriefingDto.BriefingDetail(
                UUID.randomUUID(),
                response.assessmentId(),
                resolved.sourceType(),
                resolved.sourceRef(),
                resolved.subjectTitle(),
                analysis.getAnalysisId().toString(),
                analysis.getAnalysisId(),
                analysis.getEventTitle(),
                erp.erpMaterialId(),
                erp.erpSupplierId(),
                erp.erpContractId(),
                erp.primaryContractId() != null ? erp.primaryContractId() : resolved.contractId(),
                erp.materialName(),
                resolved.materialCategory(),
                analysis.getImpactDomain(),
                composite,
                finalLevel,
                finalScore,
                response.briefing(),
                nullToEmpty(response.riskReasons()),
                nullToEmpty(response.recommendedActions()),
                evidence,
                findings,
                chain,
                verification,
                triggerType,
                asOf);
    }

    // ---------------------------------------------------------------- 보조

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof UserDetails details
                ? details.getUsername()
                : authentication.getName();
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + "는 숫자여야 합니다.");
        }
    }

    private static BigDecimal numeric(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        return source.get(key) instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
    }

    private static Integer integer(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        return source.get(key) instanceof Number number ? number.intValue() : null;
    }

    private static String text(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        return source.get(key) instanceof String value ? value : null;
    }

    private static boolean flag(Map<String, Object> source, String key) {
        return source != null && Boolean.TRUE.equals(source.get(key));
    }

    private static Long firstLong(List<Map<String, Object>> findings, String key) {
        Object value = firstValue(findings, key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer firstInteger(List<Map<String, Object>> findings, String key) {
        Object value = firstValue(findings, key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object firstValue(List<Map<String, Object>> findings, String key) {
        return findings.isEmpty() ? null : findings.get(0).get(key);
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /** 점수 컬럼(NUMERIC(5,1))과 자릿수를 맞춘다. 반올림 규칙도 PostgreSQL과 같은 HALF_UP이다. */
    private static BigDecimal scale1(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String upperOrNull(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}

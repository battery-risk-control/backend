package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventDetail;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventItem;
import com.example.batteryrisk.dto.RiskMonitoringDto.ExternalSignal;
import com.example.batteryrisk.dto.RiskMonitoringDto.ProcurementRisk;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 1계층 구매팀 "리스크 모니터링" 화면의 이벤트 목록·상세, 그리고 화면에서 직접 누르는
 * "ERP·계약 영향 분석"(멀티에이전트 실행)을 담당한다.
 *
 * <p><b>원천은 수집 뉴스({@code raw_events})다.</b> 화면이 보여주는 것은 GDELT 15분 스케줄러가
 * 모아 번역한 기사 목록이고, 각 기사에 F3 분석·멀티에이전트 결과가 붙었으면 붙은 만큼 얹는다.
 * 분석을 원천으로 삼으면({@link RiskEventService#riskBoard()}처럼) 분석이 안 돈 기사가 화면에서
 * 통째로 사라져 "수집은 되는데 목록이 비어 있다"가 된다 — 기본 운영 모드가
 * {@code app.collection.analysis-enabled=false}라 그 상태가 오히려 정상이다.
 *
 * <p><b>등급과 신뢰도의 관계</b>(요구사항의 핵심):
 * <ul>
 *   <li>멀티에이전트 <b>실행 전</b> — 등급은 외부신호(severity)만으로 매긴 <i>잠정값</i>이다.
 *       그래도 심각/주의/정상을 보여주되 신뢰도 배지(경고/참고)를 함께 띄워 잠정임을 밝힌다.</li>
 *   <li>멀티에이전트 <b>실행 후</b> — 등급이 종합 위험도({@code procurement_risk_level})로 <b>갱신</b>되고
 *       신뢰도는 "확정"이 되어 배지가 사라진다.</li>
 * </ul>
 * 이 판정은 {@link RiskEventService}의 공개 화면 규칙과 같은 함수를 쓴다 — 같은 기사가 화면마다
 * 다른 등급으로 보이지 않게 하기 위해서다. 다만 공개 속보는 확정이 아니면 등급을 <b>감추는</b> 반면
 * 이 화면은 잠정 표기와 함께 <b>보여준다</b>. 비로그인 방문자에게는 미검증 등급을 안 보여주는 편이
 * 안전하지만, 구매 담당자에게는 "아직 검증 전인 심각 건"이야말로 먼저 봐야 할 항목이기 때문이다.
 */
@Service
public class RiskMonitoringService {
    private static final Logger log = LoggerFactory.getLogger(RiskMonitoringService.class);

    /** 중복 제거·자재 필터 전에 훑을 최신 뉴스 건수. 노출 상한보다 넉넉히 잡는다. */
    private static final int SCAN_SIZE = 400;

    /** 목록 노출 상한. 인증 화면이지만 상한이 없으면 스캔 구간이 통째로 나간다. */
    private static final int MAX_LIMIT = 200;

    /** 조회 기간 상한(일). 화면 필터는 최근 7일이 기본이다. */
    private static final int MAX_DAYS = 180;

    private static final String NEWS_DATA_TYPE = "NEWS";
    private static final String ANALYSIS_STATUS_COMPLETED = "COMPLETED";

    /** severity_engine이 공급망 무관 기사에 붙이는 reason code. */
    private static final String NOT_RELEVANT_REASON_CODE = "NOT_RELEVANT";

    /** 멀티에이전트를 태워도 ERP 노드가 통째로 건너뛰어지는 도메인. */
    private static final String IRRELEVANT_IMPACT_DOMAIN = "IRRELEVANT";

    private final RawEventRepository rawEventRepository;
    private final AnalysisRepository analysisRepository;
    private final ProcurementRiskRepository procurementRiskRepository;
    private final ErpExposureRequestService erpExposureRequestService;
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;

    public RiskMonitoringService(
            RawEventRepository rawEventRepository,
            AnalysisRepository analysisRepository,
            ProcurementRiskRepository procurementRiskRepository,
            ErpExposureRequestService erpExposureRequestService,
            MultiAgentOrchestrationService multiAgentOrchestrationService) {
        this.rawEventRepository = rawEventRepository;
        this.analysisRepository = analysisRepository;
        this.procurementRiskRepository = procurementRiskRepository;
        this.erpExposureRequestService = erpExposureRequestService;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
    }

    /**
     * 이벤트 목록. 최신 수집순으로, 같은 기사(제목 기준)는 한 번만 남긴다.
     *
     * <p><b>자재가 특정된 기사만 내보낸다.</b> GDELT 트리아지는 생산국 이벤트를 폭넓게 통과시켜
     * 공급망과 무관한 기사(정치·사건사고 등)가 상당수 섞이는데, 구매팀 리스크 목록에 그런 기사가
     * 올라오면 화면이 쓸모없어진다 — 실측에서도 최근 수집분 대부분이 그런 기사였다.
     *
     * @param grade    "심각/주의/정상". null/공백이면 전체
     * @param country  ISO 3166-1 alpha-2. null/공백이면 전체
     * @param material 자재 표기명("리튬") 또는 대분류("LITHIUM"). null/공백이면 전체
     * @param days     최근 N일. 1..{@value #MAX_DAYS}로 잘린다
     * @param limit    노출 건수. 1..{@value #MAX_LIMIT}로 잘린다
     */
    public List<EventItem> list(String grade, String country, String material, int days, int limit) {
        int cappedLimit = clamp(limit, MAX_LIMIT);
        Instant since = Instant.now().minus(clamp(days, MAX_DAYS), ChronoUnit.DAYS);
        String countryFilter = upperOrNull(country);
        String gradeFilter = trimToNull(grade);
        String materialFilter = trimToNull(material);

        PageRequest scanWindow = PageRequest.of(0, SCAN_SIZE);
        List<RawEvent> events = countryFilter == null
                ? rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(
                        NEWS_DATA_TYPE, scanWindow)
                : rawEventRepository.findByDataTypeAndCountryCodeAndTitleIsNotNullOrderByCollectedAtDesc(
                        NEWS_DATA_TYPE, countryFilter, scanWindow);

        Map<UUID, Analysis> analysesById = loadAnalyses(events);
        Map<UUID, String> riskLevelsByAnalysisId =
                procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(analysesById.keySet());

        Map<String, EventItem> unique = new LinkedHashMap<>();
        for (RawEvent event : events) {
            if (event.getCollectedAt().isBefore(since)) {
                continue;
            }
            Analysis analysis = analysisOf(analysesById, event);
            String riskLevel = analysis == null ? null : riskLevelsByAnalysisId.get(analysis.getAnalysisId());
            unique.putIfAbsent(event.getTitle().trim(), toItem(event, analysis, riskLevel));
        }

        return unique.values().stream()
                .filter(item -> !RiskEventService.UNCLASSIFIED_MATERIAL.equals(item.material()))
                .filter(item -> gradeFilter == null || gradeFilter.equals(item.grade()))
                .filter(item -> materialFilter == null || matchesMaterial(item.material(), materialFilter))
                .limit(cappedLimit)
                .toList();
    }

    /** 이벤트 상세. 목록의 event_id(=raw_events.id)로 조회한다. */
    public EventDetail detail(long eventId) {
        RawEvent event = findNewsEvent(eventId);
        Analysis analysis = findAnalysis(event);
        ProcurementRiskDto.Assessment assessment = findAssessment(analysis);
        return toDetail(event, analysis, assessment);
    }

    /**
     * "ERP·계약 영향 분석" — 이 기사 하나에 대해 멀티에이전트(ERP Agent · 계약 RAG Agent ·
     * 위험도 합산 · 브리핑 · 검증)를 지금 실행하고, 갱신된 상세를 돌려준다.
     *
     * <p>실행이 끝나면 {@code procurement_risk_assessments}에 종합 점수가 남으므로, 같은 기사를
     * 다시 조회해도 등급이 종합 위험도 기준으로 유지된다(신뢰도는 "확정"). 즉 이 호출은
     * 화면 상태를 잠정 → 확정으로 <b>영구히</b> 옮긴다.
     *
     * <p>실행 가능 여부는 {@link #erpImpactBlockedReason}이 판정한다. 화면은 상세의
     * {@code erp_impact_available}로 버튼을 미리 비활성화하지만, 화면을 믿지 않고 여기서도 막는다.
     *
     * @param useLlm 브리핑 문구 생성에 LLM을 쓸지. 기본은 false — 등급 갱신에는 LLM이 필요 없고,
     *               버튼 한 번이 곧 비용이 되는 경로라 켜는 쪽을 명시적 선택으로 둔다.
     */
    public EventDetail runErpImpact(long eventId, boolean useLlm) {
        RawEvent event = findNewsEvent(eventId);
        Analysis analysis = findAnalysis(event);

        String blockedReason = erpImpactBlockedReason(analysis);
        if (blockedReason != null) {
            throw new BusinessException(ErrorCode.ERP_IMPACT_NOT_AVAILABLE, blockedReason);
        }

        String materialCategory = resolveMaterialCategory(event, analysis);
        ErpExposureRequestService.ResolvedErpTarget target =
                erpExposureRequestService.resolveErpTarget(analysis.getCountryCode(), materialCategory);
        if (target == null) {
            throw new BusinessException(
                    ErrorCode.ERP_IMPACT_NOT_AVAILABLE,
                    "자재 대분류 " + materialCategory + "에 연결된 ERP 자재·주 공급사가 없습니다.");
        }

        // analysisId를 실어 보내면 외부신호(가중치 0.35)를 analyses의 severity_score에서 읽는다 —
        // 화면이 이미 보여준 외부신호 점수와 종합 점수의 입력이 어긋나지 않게 하는 유일한 경로다.
        MultiAgentDto.GenerateRequest request = new MultiAgentDto.GenerateRequest(
                analysis.getAnalysisId().toString(),
                analysis.getAnalysisId(),
                analysis.getEventTitle(),
                analysis.getEventContent() == null ? "" : analysis.getEventContent(),
                analysis.getSummaryKr() == null ? "" : analysis.getSummaryKr(),
                analysis.getImpactDomain(),
                analysis.getImpactDomain(),
                null, null,
                target.erpMaterialId(),
                target.erpSupplierId(),
                analysis.getCountryCode(),
                OffsetDateTime.now(),
                useLlm);

        MultiAgentDto.Response response = multiAgentOrchestrationService.generate(request);
        log.info("리스크 모니터링 ERP·계약 영향 분석 완료: eventId={}, analysisId={}, level={}, kgMatched={}",
                eventId, analysis.getAnalysisId(), response.procurementRiskLevel(), target.kgMatched());

        return toDetail(event, analysis, findAssessment(analysis));
    }

    /**
     * 멀티에이전트를 돌릴 수 없는 이유. 돌릴 수 있으면 null.
     *
     * <p>여기서 걸러내는 건 전부 "돌려도 의미 없는" 경우다 — {@link MultiAgentOrchestrationService}가
     * 어차피 같은 조건에서 예외를 던지거나 ERP 노드를 통째로 건너뛴다. 화면이 버튼을 눌러보기 전에
     * 알 수 있도록 같은 판정을 상세 응답에도 실어 보낸다.
     */
    private static String erpImpactBlockedReason(Analysis analysis) {
        if (analysis == null) {
            return "아직 AI 분석(F3)이 실행되지 않은 기사입니다.";
        }
        if (!ANALYSIS_STATUS_COMPLETED.equals(analysis.getStatus())
                || analysis.getSeverity() == null || analysis.getSeverityScore() == null) {
            return "외부신호 점수가 아직 산출되지 않았습니다.";
        }
        if (hasNotRelevantReason(analysis)) {
            return "공급망과 무관한 뉴스로 판정되었습니다.";
        }
        if (analysis.getImpactDomain() == null
                || IRRELEVANT_IMPACT_DOMAIN.equalsIgnoreCase(analysis.getImpactDomain())) {
            return "영향 도메인이 '무관'으로 판정되어 ERP 영향을 계산할 수 없습니다.";
        }
        if (analysis.getCountryCode() == null || analysis.getCountryCode().isBlank()) {
            return "기사에서 국가를 특정하지 못해 ERP 영향을 계산할 수 없습니다.";
        }
        return null;
    }

    /**
     * {@code analyses.reason_codes}는 {@code String.join(",")}로 저장한 CSV라 split 후 비교한다
     * ({@link MultiAgentOrchestrationService}와 같은 규칙).
     */
    private static boolean hasNotRelevantReason(Analysis analysis) {
        return reasonCodes(analysis).contains(NOT_RELEVANT_REASON_CODE);
    }

    private static List<String> reasonCodes(Analysis analysis) {
        String raw = analysis == null ? null : analysis.getReasonCodes();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(code -> !code.isEmpty()).toList();
    }

    /**
     * 멀티에이전트에 넘길 자재 대분류.
     *
     * <p>F3는 severity가 CRITICAL/WARNING인 분석에만 {@code material_category}를 붙이므로
     * (F9 대체 공급사 추천이 돈 경우에만 채워진다) NORMAL 기사는 비어 있다. 그때는 제목·본문
     * 키워드로 추정한다 — 목록이 이미 자재가 특정된 기사만 보여주고 있어 추정이 실패할 일은
     * 거의 없지만, 방어적으로 막아 둔다.
     */
    private static String resolveMaterialCategory(RawEvent event, Analysis analysis) {
        if (analysis.getMaterialCategory() != null && !analysis.getMaterialCategory().isBlank()) {
            return analysis.getMaterialCategory();
        }
        String guessed = RiskEventService.guessMaterialCategory(event.getTitle(), event.getContent());
        if (guessed == null) {
            throw new BusinessException(
                    ErrorCode.ERP_IMPACT_NOT_AVAILABLE, "기사에서 영향 원자재를 특정하지 못했습니다.");
        }
        return guessed;
    }

    /** title이 null인 행은 GDELT 커서 전진용 sentinel이라 화면에 존재하지 않는 것으로 취급한다. */
    private RawEvent findNewsEvent(long eventId) {
        return rawEventRepository.findById(eventId)
                .filter(candidate -> NEWS_DATA_TYPE.equals(candidate.getDataType()))
                .filter(candidate -> candidate.getTitle() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RISK_EVENT_NOT_FOUND));
    }

    private Analysis findAnalysis(RawEvent event) {
        return event.getTriggeredAnalysisId() == null
                ? null
                : analysisRepository.findById(event.getTriggeredAnalysisId()).orElse(null);
    }

    private ProcurementRiskDto.Assessment findAssessment(Analysis analysis) {
        return analysis == null
                ? null
                : procurementRiskRepository.findLatestByAnalysisId(analysis.getAnalysisId()).orElse(null);
    }

    /**
     * 뉴스에 붙은 분석. 분석이 안 붙은 뉴스는 null이다.
     *
     * <p>{@code map.get(event.getTriggeredAnalysisId())}로 바로 부르면 안 된다 — 스캔 구간의 모든
     * 뉴스에 분석이 없으면 {@link #loadAnalyses}가 {@code Map.of()}(불변 맵)를 돌려주는데, 거기에
     * null 키로 get을 하면 NPE가 난다. 그 조합은 예외 상황이 아니라 <b>기본 운영 모드</b>다
     * ({@code app.collection.analysis-enabled=false}면 수집만 하고 분석은 돌지 않는다).
     */
    private static Analysis analysisOf(Map<UUID, Analysis> analysesById, RawEvent event) {
        UUID analysisId = event.getTriggeredAnalysisId();
        return analysisId == null ? null : analysesById.get(analysisId);
    }

    /** 뉴스에 붙은 분석을 한 번에 조회한다(분석이 없는 뉴스뿐이면 쿼리를 생략). */
    private Map<UUID, Analysis> loadAnalyses(List<RawEvent> events) {
        List<UUID> analysisIds = events.stream()
                .map(RawEvent::getTriggeredAnalysisId)
                .filter(Objects::nonNull)
                .toList();
        if (analysisIds.isEmpty()) {
            return Map.of();
        }
        return analysisRepository.findAllById(analysisIds).stream()
                .collect(Collectors.toMap(Analysis::getAnalysisId, analysis -> analysis));
    }

    private static EventItem toItem(RawEvent event, Analysis analysis, String riskLevel) {
        Headline headline = Headline.of(event);
        return new EventItem(
                event.getId(),
                gradeOf(analysis, riskLevel),
                RiskEventService.newsConfidenceLabel(analysis, riskLevel),
                riskLevel != null,
                headline.display(),
                headline.original(),
                headline.translated(),
                materialOf(event, analysis),
                event.getCountryCode(),
                RiskEventService.countryNameKo(event.getCountryCode()),
                event.getCollectedAt(),
                event.getSource());
    }

    private static EventDetail toDetail(
            RawEvent event, Analysis analysis, ProcurementRiskDto.Assessment assessment) {
        // 조기 종료된 실행의 등급(항상 NORMAL·0점)은 판정으로 쓰지 않는다 — isComposite 참고.
        String riskLevel = assessment != null && isComposite(assessment)
                ? assessment.procurementRiskLevel()
                : null;
        Headline headline = Headline.of(event);
        String blockedReason = erpImpactBlockedReason(analysis);
        return new EventDetail(
                event.getId(),
                gradeOf(analysis, riskLevel),
                RiskEventService.newsConfidenceLabel(analysis, riskLevel),
                riskLevel != null,
                headline.display(),
                headline.original(),
                headline.translated(),
                analysis == null ? null : analysis.getSummaryKr(),
                materialOf(event, analysis),
                analysis == null ? null : analysis.getImpactDomain(),
                event.getCountryCode(),
                RiskEventService.countryNameKo(event.getCountryCode()),
                RiskEventService.coordinatesOf(event.getCountryCode()),
                event.getCollectedAt(),
                event.getSource(),
                RiskEventService.linkableUrl(event.getSourceUrl()),
                toExternalSignal(event, analysis),
                toProcurementRisk(assessment),
                blockedReason == null,
                blockedReason);
    }

    /**
     * 화면 등급. 멀티에이전트 종합 등급이 있으면 그것을, 없으면 외부신호 등급을 쓴다 —
     * 이 갈림길이 "분석이 끝나면 등급이 갱신된다"는 요구사항 그 자체다.
     */
    private static String gradeOf(Analysis analysis, String riskLevel) {
        if (riskLevel != null) {
            return RiskEventService.gradeOf(riskLevel);
        }
        return analysis == null ? null : RiskEventService.gradeOf(analysis.getSeverity());
    }

    /** 분석이 자재를 특정했으면 그 값을, 아니면 제목·본문 키워드 추정값을 쓴다. */
    private static String materialOf(RawEvent event, Analysis analysis) {
        if (analysis != null && analysis.getMaterialCategory() != null) {
            return RiskEventService.materialNameKo(analysis.getMaterialCategory());
        }
        return RiskEventService.materialNameKo(
                RiskEventService.guessMaterialCategory(event.getTitle(), event.getContent()));
    }

    /**
     * 외부신호 패널. Goldstein은 분석이 실제로 먹은 값을 우선하고, 없으면(V22 이전에 분석된 행)
     * 수집 원본 값으로 폴백한다 — 둘은 보통 같지만 FastAPI가 병합·기본값을 적용하면 갈릴 수 있다.
     */
    private static ExternalSignal toExternalSignal(RawEvent event, Analysis analysis) {
        if (analysis == null) {
            return null;
        }
        Double goldstein = analysis.getGoldsteinScale() != null
                ? analysis.getGoldsteinScale()
                : event.getGoldsteinScale();
        return new ExternalSignal(
                goldstein,
                analysis.getToneScore(),
                analysis.getNewsCount(),
                analysis.getSeverityScore(),
                analysis.getSeverity(),
                reasonCodes(analysis));
    }

    private static ProcurementRisk toProcurementRisk(ProcurementRiskDto.Assessment assessment) {
        if (assessment == null) {
            return null;
        }
        return new ProcurementRisk(
                assessment.assessmentId(),
                isComposite(assessment),
                assessment.procurementRiskLevel(),
                assessment.procurementRiskScore(),
                assessment.externalSignalScore(),
                assessment.erpExposureScore(),
                assessment.contractGapScore(),
                assessment.riskReasons() == null ? List.of() : assessment.riskReasons(),
                assessment.reviewPassed(),
                assessment.assessedAt());
    }

    /**
     * ERP·계약 노드까지 실제로 돌아 종합 점수가 나온 실행인지.
     *
     * <p>LangGraph는 KG 게이트({@code analyze_kg_context_node})에서 매칭이 없거나 재고가 충분하면
     * ERP·계약 노드를 건너뛰고 조기 종료하는데({@code build_no_shortage_briefing}), 그때도 행은
     * 남으면서 점수 0 · 등급 NORMAL로 기록된다. <b>그 0을 종합 판정으로 읽으면 안 된다</b> —
     * "평가해보니 정상"이 아니라 "평가하지 못했다"이기 때문이다.
     *
     * <p>판별 기준으로 ERP 노출도 점수의 존재를 쓴다. 조기 종료 경로는 {@code erp_assessment}를
     * 빈 dict로 두므로 이 값이 반드시 null이고, 정상 실행은 반드시 값이 있다. 등급 문자열이나
     * 근거 문구를 파싱하는 것보다 안정적이다.
     */
    private static boolean isComposite(ProcurementRiskDto.Assessment assessment) {
        return assessment.erpExposureScore() != null;
    }

    /** 표시용/원문 헤드라인과 번역 여부. 번역 스케줄러가 뒤늦게 채우므로 미번역도 정상 노출된다. */
    private record Headline(String display, String original, boolean translated) {
        static Headline of(RawEvent event) {
            String original = event.getTitle();
            String translatedTitle = event.getTitleKo();
            boolean translated = translatedTitle != null && !translatedTitle.isBlank();
            return new Headline(translated ? translatedTitle : original, original, translated);
        }
    }

    /** 자재 필터는 표기명("리튬")과 대분류("LITHIUM") 양쪽을 받는다 — 화면과 API 둘 다 편하게. */
    private static boolean matchesMaterial(String itemMaterial, String filter) {
        return itemMaterial.equals(filter)
                || itemMaterial.equals(RiskEventService.materialNameKo(filter.toUpperCase(Locale.ROOT)));
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    private static String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

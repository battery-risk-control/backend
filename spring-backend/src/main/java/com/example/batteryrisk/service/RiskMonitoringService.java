package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.dto.RiskMonitoringDto;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventDetail;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventItem;
import com.example.batteryrisk.dto.RiskMonitoringDto.ExternalSignal;
import com.example.batteryrisk.dto.RiskMonitoringDto.ProcurementRisk;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final AiBriefingRepository aiBriefingRepository;

    public RiskMonitoringService(
            RawEventRepository rawEventRepository,
            AnalysisRepository analysisRepository,
            ProcurementRiskRepository procurementRiskRepository,
            ErpExposureRequestService erpExposureRequestService,
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            AiBriefingRepository aiBriefingRepository) {
        this.rawEventRepository = rawEventRepository;
        this.analysisRepository = analysisRepository;
        this.procurementRiskRepository = procurementRiskRepository;
        this.erpExposureRequestService = erpExposureRequestService;
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.aiBriefingRepository = aiBriefingRepository;
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

        // 날짜·국가·자재 필터와 제목 중복 제거를 SQL에서 끝낸다. 예전에는 최신 400건을 먼저
        // 가져와 Java에서 걸렀는데, 그러면 관련 뉴스가 그 창 밖으로 밀려나 조회조차 되지
        // 않았다(실측 최근 7일: 자재가 분류된 고유 뉴스 14건 중 최신 400건 안에는 4건뿐).
        //
        // 등급 필터만 Java에 남는다 — 등급은 브리핑 조회 결과에서 나오므로 이 쿼리 안에서
        // 계산할 수 없다. 그래서 SQL은 MAX_LIMIT까지 가져오고 등급을 거른 뒤 최종 limit을
        // 적용한다. 자르는 시점이 "전체 뉴스"가 아니라 "이미 걸러진 관련 뉴스"라는 게 차이다.
        List<RawEvent> events = rawEventRepository.findRiskMonitoringCandidates(
                since, countryFilter, toMaterialCategory(materialFilter), MAX_LIMIT);

        Map<UUID, Analysis> analysesById = loadAnalyses(events);
        // 확정 판정을 지도·속보와 같은 기준으로 맞춘다 — "이 뉴스의 완결된 브리핑이 있는가".
        // 종합 평가만 보면 계약·자재 화면이 이 뉴스를 외부신호로 끌어다 쓴 실행까지 확정으로
        // 세어, 같은 기사가 화면마다 다른 배지를 단다.
        Map<UUID, AiBriefingRepository.NewsBriefingRef> newsBriefings =
                aiBriefingRepository.findCompletedNewsBriefingsByAnalysisIds(analysesById.keySet());

        List<EventItem> items = new ArrayList<>();
        for (RawEvent event : events) {
            Analysis analysis = analysisOf(analysesById, event);
            AiBriefingRepository.NewsBriefingRef briefing = analysis == null
                    ? null
                    : newsBriefings.get(analysis.getAnalysisId());
            items.add(toItem(event, analysis, briefing));
        }

        return items.stream()
                .filter(item -> gradeFilter == null || gradeFilter.equals(item.grade()))
                .limit(cappedLimit)
                .toList();
    }

    /**
     * 화면이 넘긴 자재 필터를 대분류 코드로 정규화한다. 화면은 표기명("리튬")과 대분류("LITHIUM")를
     * 둘 다 보내므로 SQL에 넘기기 전에 한 형태로 모은다.
     *
     * <p>어느 쪽에도 없는 값이면 대문자 그대로 넘긴다 — 결과가 0건이 되는데, 그게 "없는 자재를
     * 물었다"는 사실에 맞는 응답이다(임의로 전체를 돌려주면 필터가 무시된 것처럼 보인다).
     */
    private static String toMaterialCategory(String filter) {
        if (filter == null) {
            return null;
        }
        String upper = filter.toUpperCase(Locale.ROOT);
        if (RiskEventService.MATERIAL_NAME_KO.containsKey(upper)) {
            return upper;
        }
        return RiskEventService.MATERIAL_NAME_KO.entrySet().stream()
                .filter(entry -> entry.getValue().equals(filter))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(upper);
    }

    /** 이벤트 상세. 목록의 event_id(=raw_events.id)로 조회한다. */
    public EventDetail detail(long eventId) {
        RawEvent event = findNewsEvent(eventId);
        Analysis analysis = findAnalysis(event);
        return toDetail(event, analysis, loadComposite(analysis), newsBriefingOf(analysis));
    }

    /**
     * <b>폐기 예정(2026-08-03).</b> "ERP·계약 영향 분석" — 멀티에이전트를 실행하고 갱신된
     * 상세를 돌려준다.
     *
     * <p>실행하면 {@code procurement_risk_assessments}에 종합 점수는 남지만
     * <b>{@code ai_briefings}에는 아무것도 남지 않는다.</b> 확정 판정이 "완결된 NEWS 브리핑
     * 존재"로 통일된 뒤로는, 이 경로로 실행해도 화면이 확정으로 바뀌지 않고 브리핑 본문도
     * 다시 열어볼 수 없다 — LLM 비용만 나가고 결과가 사라지는 셈이다.
     *
     * <p>대신 {@code AiBriefingService.generate}(source=NEWS)를 쓴다. 같은 멀티에이전트를
     * 돌리고 본문까지 저장해 확정이 된다.
     *
     * <p>제거하지 않고 남기는 이유: 두 프론트엔드 모두 호출하지 않는 것을 확인했지만
     * (2026-08-03) 외부에서 직접 부르는 경로가 있을 수 있다. 살리려면 브리핑 저장을 공통
     * 로직으로 빼야 하는데({@code AiBriefingService} ↔ 이 클래스가 순환이라) 그만한 값이
     * 있는지부터 정해야 한다.
     *
     * <p>실행 가능 여부는 {@link #erpImpactBlockedReason}이 판정한다. 화면은 상세의
     * {@code erp_impact_available}로 버튼을 미리 비활성화하지만, 화면을 믿지 않고 여기서도 막는다.
     *
     * @param useLlm 브리핑 문구 생성에 LLM을 쓸지. 기본은 false — 등급 갱신에는 LLM이 필요 없고,
     *               버튼 한 번이 곧 비용이 되는 경로라 켜는 쪽을 명시적 선택으로 둔다.
     */
    @Deprecated(since = "2026-08-03")
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

        return toDetail(event, analysis, loadComposite(analysis), newsBriefingOf(analysis));
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

    /**
     * 분석 1건의 자재별 평가를 <b>대분류 카드 하나로 접은</b> 결과.
     *
     * <p>한 분석이 자재 여러 개로 펼쳐진다 — LITHIUM·GRAPHITE는 ERP 자재가 2개씩이라 점수화
     * 스케줄러가 자재마다 따로 평가한다. 화면 카드는 대분류 하나이므로 접어야 하는데,
     * <b>가장 심한 자재를 대표로 삼는다</b>. "최신"으로 고르면 저장 순서라는 우연이 등급을
     * 정하게 되고, 수산화리튬이 심각인데 탄산리튬이 나중에 저장됐다는 이유로 정상이 떠버린다.
     */
    private record CompositeView(
            /** 유효 평가 중 가장 심한 것. 하나도 없으면 null이라 등급은 외부신호 기준을 유지한다. */
            ProcurementRiskDto.Assessment representative,
            List<ProcurementRiskDto.Assessment> materials,
            int validCount,
            RiskMonitoringDto.AttemptStatus status,
            OffsetDateTime latestAttemptAt) {

        static final CompositeView NONE = new CompositeView(
                null, List.of(), 0, RiskMonitoringDto.AttemptStatus.NOT_RUN, null);

        String riskLevel() {
            return representative == null ? null : representative.procurementRiskLevel();
        }
    }

    /** 등급 정렬: 심한 것 먼저, 같으면 점수 높은 것 먼저. 리포지토리의 SEVERITY_RANK_SQL과 같은 순서다. */
    private static final Map<String, Integer> SEVERITY_RANK =
            Map.of("CRITICAL", 1, "WARNING", 2, "NORMAL", 3);

    private static int severityRank(String riskLevel) {
        // Map.of는 불변 맵이라 get(null)이 NPE를 던진다 — 먼저 막는다.
        return riskLevel == null ? 4 : SEVERITY_RANK.getOrDefault(riskLevel, 4);
    }

    private static final Comparator<ProcurementRiskDto.Assessment> WORST_FIRST =
            Comparator.<ProcurementRiskDto.Assessment>comparingInt(
                            assessment -> severityRank(assessment.procurementRiskLevel()))
                    .thenComparing(
                            assessment -> assessment.procurementRiskScore() == null
                                    ? BigDecimal.ZERO : assessment.procurementRiskScore(),
                            Comparator.reverseOrder());

    private CompositeView loadComposite(Analysis analysis) {
        if (analysis == null) {
            return CompositeView.NONE;
        }
        List<ProcurementRiskDto.Assessment> materials =
                procurementRiskRepository.findMaterialAssessments(analysis.getAnalysisId());
        if (materials.isEmpty()) {
            return CompositeView.NONE;
        }
        List<ProcurementRiskDto.Assessment> valid =
                materials.stream().filter(RiskMonitoringService::isComposite).toList();
        RiskMonitoringDto.AttemptStatus status;
        if (valid.isEmpty()) {
            status = RiskMonitoringDto.AttemptStatus.EARLY_TERMINATED;
        } else if (valid.size() == materials.size()) {
            status = RiskMonitoringDto.AttemptStatus.COMPLETED;
        } else {
            status = RiskMonitoringDto.AttemptStatus.PARTIAL_SUCCESS;
        }
        return new CompositeView(
                valid.stream().min(WORST_FIRST).orElse(null),
                materials,
                valid.size(),
                status,
                procurementRiskRepository.findLatestAttemptAt(analysis.getAnalysisId()).orElse(null));
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

    /**
     * 목록 한 줄. 등급·확정·브리핑 id를 <b>같은 브리핑 행 하나</b>에서 조립한다 — 지도·속보와
     * 같은 규칙이라 같은 기사가 화면마다 다른 배지를 달지 않는다.
     */
    private static EventItem toItem(
            RawEvent event, Analysis analysis, AiBriefingRepository.NewsBriefingRef briefing) {
        boolean confirmed = briefing != null;
        // 등급은 완결된 뉴스 브리핑이 있으면 그 종합등급, 없으면 외부신호 등급이다.
        String riskLevel = confirmed ? briefing.procurementRiskLevel() : null;
        Headline headline = Headline.of(event);
        return new EventItem(
                confirmed ? briefing.briefingId() : null,
                event.getId(),
                gradeOf(analysis, riskLevel),
                RiskEventService.newsConfidenceLabel(analysis, confirmed),
                confirmed,
                headline.display(),
                headline.original(),
                headline.translated(),
                materialOf(event, analysis),
                event.getCountryCode(),
                RiskEventService.countryNameKo(event.getCountryCode()),
                event.getCollectedAt(),
                event.getSource());
    }

    /**
     * 상세 조립. <b>목록과 같은 집계(대표 자재의 유효 평가)를 쓴다</b> — 예전에는 상세만
     * "최신 행 아무거나"를 봐서, 재실행이 조기 종료되면 목록은 확정인데 상세는 잠정으로 갈렸다.
     */
    /**
     * 이 분석의 완결된 뉴스 브리핑. 목록은 배치로 뽑지만 상세는 단건이라 여기서 바로 본다 —
     * 목록과 상세가 같은 조건을 써야 "목록은 참고, 상세는 확정"이 생기지 않는다.
     */
    private AiBriefingRepository.NewsBriefingRef newsBriefingOf(Analysis analysis) {
        if (analysis == null) {
            return null;
        }
        return aiBriefingRepository
                .findCompletedNewsBriefingsByAnalysisIds(Set.of(analysis.getAnalysisId()))
                .get(analysis.getAnalysisId());
    }

    private static EventDetail toDetail(
            RawEvent event, Analysis analysis, CompositeView composite,
            AiBriefingRepository.NewsBriefingRef briefing) {
        // 등급·확정은 목록과 같은 기준(완결된 뉴스 브리핑)을 쓴다. composite는 아래
        // procurementRisk 블록이 계속 쓴다 — "종합 평가가 어디까지 돌았는가"는 확정 여부와
        // 다른 축이라(자재 커버리지·조기 종료 사유) 그 정보는 그대로 유지한다.
        boolean confirmed = briefing != null;
        String riskLevel = confirmed ? briefing.procurementRiskLevel() : null;
        Headline headline = Headline.of(event);
        String blockedReason = erpImpactBlockedReason(analysis);
        return new EventDetail(
                confirmed ? briefing.briefingId() : null,
                event.getId(),
                gradeOf(analysis, riskLevel),
                RiskEventService.newsConfidenceLabel(analysis, confirmed),
                confirmed,
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
                toProcurementRisk(composite),
                blockedReason == null,
                blockedReason,
                composite.status().name(),
                composite.latestAttemptAt());
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

    /**
     * 종합 평가 블록. <b>대표 자재의 값</b>을 헤드라인 점수로 쓰고, 접히기 전 자재별 결과를 함께 싣는다.
     *
     * <p>유효 평가가 하나도 없으면(전부 조기 종료) 대표가 없으므로 가장 최근 시도를 헤드라인에
     * 두되 {@code completed=false}로 내린다 — 점수는 의미가 없지만 사유는 보여줘야 하기 때문이다.
     */
    private static ProcurementRisk toProcurementRisk(CompositeView composite) {
        if (composite.materials().isEmpty()) {
            return null;
        }
        ProcurementRiskDto.Assessment head = composite.representative() != null
                ? composite.representative()
                : composite.materials().get(0);
        return new ProcurementRisk(
                head.assessmentId(),
                composite.representative() != null,
                head.procurementRiskLevel(),
                head.procurementRiskScore(),
                head.externalSignalScore(),
                head.erpExposureScore(),
                head.contractGapScore(),
                head.riskReasons() == null ? List.of() : head.riskReasons(),
                head.reviewPassed(),
                head.assessedAt(),
                composite.representative() == null ? null : head.erpMaterialId(),
                composite.validCount(),
                composite.materials().size(),
                composite.materials().stream()
                        .sorted(WORST_FIRST)
                        .map(RiskMonitoringService::toMaterialAssessment)
                        .toList());
    }

    private static RiskMonitoringDto.MaterialAssessment toMaterialAssessment(
            ProcurementRiskDto.Assessment assessment) {
        boolean valid = isComposite(assessment);
        return new RiskMonitoringDto.MaterialAssessment(
                assessment.erpMaterialId(),
                valid,
                // 조기 종료 행의 등급·점수(항상 NORMAL·0)는 판정이 아니라 잡음이라 내보내지 않는다.
                valid ? assessment.procurementRiskLevel() : null,
                valid ? assessment.procurementRiskScore() : null,
                assessment.erpExposureScore(),
                assessment.contractGapScore(),
                assessment.riskReasons() == null ? List.of() : assessment.riskReasons(),
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

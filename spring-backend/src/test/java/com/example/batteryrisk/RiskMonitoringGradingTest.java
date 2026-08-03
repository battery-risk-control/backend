package com.example.batteryrisk;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventDetail;
import com.example.batteryrisk.dto.RiskMonitoringDto.EventItem;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import com.example.batteryrisk.service.ErpExposureRequestService;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import com.example.batteryrisk.service.RiskMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 구매팀 리스크 모니터링 화면의 <b>잠정/확정 판정</b> 회귀 테스트.
 *
 * <p>이 화면의 핵심 요구사항은 "멀티에이전트가 아직 안 돌았으면 등급을 보여주되 잠정임을 함께
 * 밝히고, 다 돌았으면 종합 위험도로 등급을 갱신하며 잠정 배지를 없앤다"이다. 판정이 어긋나면
 * 화면이 조용히 거짓말을 하므로(검증 안 된 등급을 확정으로 표기) 규칙을 테스트로 고정한다.
 *
 * <p>특히 <b>KG 게이트 조기 종료</b>를 확정으로 세지 않는지 확인한다 — LangGraph는 KG 매칭이
 * 없으면 ERP·계약 노드를 건너뛰고 0점·NORMAL로 기록하는데, 그걸 종합 판정으로 읽으면
 * "평가하지 못한 것"이 "평가해보니 정상"으로 둔갑한다.
 */
class RiskMonitoringGradingTest {
    private RawEventRepository rawEventRepository;
    private AnalysisRepository analysisRepository;
    private ProcurementRiskRepository procurementRiskRepository;
    private RiskMonitoringService service;

    @BeforeEach
    void setUp() {
        rawEventRepository = mock(RawEventRepository.class);
        analysisRepository = mock(AnalysisRepository.class);
        procurementRiskRepository = mock(ProcurementRiskRepository.class);
        when(procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(any())).thenReturn(Map.of());
        service = new RiskMonitoringService(
                rawEventRepository, analysisRepository, procurementRiskRepository,
                mock(ErpExposureRequestService.class), mock(MultiAgentOrchestrationService.class));
    }

    /** 멀티에이전트 미완이면 외부신호 등급을 보여주되 "참고"로 잠정임을 밝힌다. */
    @Test
    void beforeMultiAgentShowsExternalGradeAsProvisional() {
        stubList(newsEvent(1L, "CL"), completed("CL", "NORMAL", 54.1));

        EventItem item = service.list(null, null, null, 7, 50).get(0);

        assertThat(item.grade()).isEqualTo("정상");
        assertThat(item.confidenceLabel()).isEqualTo("참고");
        assertThat(item.multiAgentCompleted()).isFalse();
    }

    /** 외부신호 점수가 기준(67) 이상이면 잠정이되 "경고"로 한 단계 강한 배지를 준다. */
    @Test
    void strongExternalSignalIsMarkedWarningWhileStillProvisional() {
        stubList(newsEvent(1L, "CL"), completed("CL", "CRITICAL", 85.0));

        EventItem item = service.list(null, null, null, 7, 50).get(0);

        assertThat(item.grade()).isEqualTo("심각");
        assertThat(item.confidenceLabel()).isEqualTo("경고");
        assertThat(item.multiAgentCompleted()).isFalse();
    }

    /** 멀티에이전트가 끝나면 등급이 종합 위험도로 갱신되고 "확정"이 되어 잠정 배지가 사라진다. */
    @Test
    void completedMultiAgentOverridesGradeAndConfirms() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        stubList(newsEvent(1L, "CL"), analysis);
        // 외부신호는 NORMAL인데 종합은 CRITICAL — 종합이 이겨야 한다.
        when(procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(any()))
                .thenReturn(Map.of(analysis.getAnalysisId(), "CRITICAL"));

        EventItem item = service.list(null, null, null, 7, 50).get(0);

        assertThat(item.grade()).isEqualTo("심각");
        assertThat(item.confidenceLabel()).isEqualTo("확정");
        assertThat(item.multiAgentCompleted()).isTrue();
    }

    /** 분석(F3)이 아직 없는 기사는 등급을 지어내지 않는다 — 화면이 "미분석"으로 표시한다. */
    @Test
    void newsWithoutAnalysisHasNoGrade() {
        RawEvent event = newsEvent(1L, "CL");
        when(rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(any(), any()))
                .thenReturn(List.of(event));

        EventItem item = service.list(null, null, null, 7, 50).get(0);

        assertThat(item.grade()).isNull();
        assertThat(item.confidenceLabel()).isEqualTo("참고");
        assertThat(item.multiAgentCompleted()).isFalse();
    }

    /**
     * KG 게이트에서 조기 종료된 실행(ERP 노출도 없음)은 확정으로 세지 않는다.
     * 이게 깨지면 kg_service가 꺼져 있는 동안 모든 기사가 "종합 판정 결과 정상(확정)"이 된다.
     */
    @Test
    void shortCircuitedAssessmentIsNotCountedAsCompleted() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        stubDetail(newsEvent(1L, "CL"), analysis, assessment(null, "NORMAL", BigDecimal.ZERO));

        EventDetail detail = service.detail(1L);

        assertThat(detail.multiAgentCompleted()).isFalse();
        assertThat(detail.confidenceLabel()).isEqualTo("참고");
        assertThat(detail.grade()).isEqualTo("정상"); // 외부신호 기준 유지
        assertThat(detail.procurementRisk().completed()).isFalse();
    }

    /** ERP 노출도가 있는 실행만 종합 판정으로 인정한다. */
    @Test
    void compositeAssessmentDrivesDetailGrade() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        stubDetail(newsEvent(1L, "CL"), analysis,
                assessment(BigDecimal.valueOf(82), "CRITICAL", BigDecimal.valueOf(78.5)));

        EventDetail detail = service.detail(1L);

        assertThat(detail.multiAgentCompleted()).isTrue();
        assertThat(detail.grade()).isEqualTo("심각");
        assertThat(detail.confidenceLabel()).isEqualTo("확정");
        assertThat(detail.procurementRisk().completed()).isTrue();
    }

    /**
     * <b>한 분석이 자재 여러 개로 펼쳐지면 가장 심한 자재가 카드 등급이 된다.</b>
     *
     * <p>"최신"으로 고르면 저장 순서라는 우연이 등급을 정한다 — 수산화리튬이 심각인데 탄산리튬이
     * 나중에 저장됐다는 이유로 정상이 뜨면 담당자가 심각 건을 놓친다.
     */
    @Test
    void worstMaterialDrivesCardGrade() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        stubDetail(newsEvent(1L, "CL"), analysis,
                assessment("MAT-LI-CARB", BigDecimal.valueOf(19), "NORMAL", BigDecimal.valueOf(33)),
                assessment("MAT-LI-OH", BigDecimal.valueOf(82), "CRITICAL", BigDecimal.valueOf(82)));

        EventDetail detail = service.detail(1L);

        assertThat(detail.grade()).isEqualTo("심각");
        assertThat(detail.procurementRisk().representativeMaterialId()).isEqualTo("MAT-LI-OH");
        assertThat(detail.procurementRisk().validMaterialCount()).isEqualTo(2);
        assertThat(detail.procurementRisk().targetMaterialCount()).isEqualTo(2);
        assertThat(detail.latestAttemptStatus()).isEqualTo("COMPLETED");
    }

    /**
     * 자재 일부만 성공하면 등급은 성공한 쪽에서 나오되 커버리지를 따로 알린다.
     * 등급 신뢰도(multiAgentCompleted)와 "전부 돌았는가"(latestAttemptStatus)는 다른 축이다.
     */
    @Test
    void partialSuccessKeepsGradeButReportsCoverage() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        stubDetail(newsEvent(1L, "CL"), analysis,
                assessment("MAT-LI-CARB", null, "NORMAL", BigDecimal.ZERO),
                assessment("MAT-LI-OH", BigDecimal.valueOf(82), "CRITICAL", BigDecimal.valueOf(82)));

        EventDetail detail = service.detail(1L);

        assertThat(detail.grade()).isEqualTo("심각");
        assertThat(detail.multiAgentCompleted()).isTrue();
        assertThat(detail.latestAttemptStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(detail.procurementRisk().validMaterialCount()).isEqualTo(1);
        assertThat(detail.procurementRisk().targetMaterialCount()).isEqualTo(2);
    }

    /**
     * <b>목록과 상세가 같은 집계를 써야 한다.</b> 예전에는 목록만 "최신 유효 평가"를, 상세는
     * "최신 행 아무거나"를 봐서, 재실행이 조기 종료되면 목록은 확정인데 상세는 잠정으로 갈렸다
     * (2026-08-02 실측 재현). 자재별 최신 유효 평가를 고르므로 조기 종료가 더 최신이어도 살아남는다.
     */
    @Test
    void listAndDetailAgreeWhenLatestAttemptTerminatedEarly() {
        Analysis analysis = completed("CL", "NORMAL", 54.1);
        RawEvent event = newsEvent(1L, "CL");
        // 같은 자재의 유효 평가가 이미 있으므로, 리포지토리는 조기 종료 행이 아니라 이쪽을 돌려준다.
        stubDetail(event, analysis,
                assessment("MAT-LI-CARB", BigDecimal.valueOf(19), "CRITICAL", BigDecimal.valueOf(82)));
        when(rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(any(), any()))
                .thenReturn(List.of(event));
        when(analysisRepository.findAllById(any())).thenReturn(List.of(analysis));
        when(procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(any()))
                .thenReturn(Map.of(analysis.getAnalysisId(), "CRITICAL"));

        EventItem item = service.list(null, null, null, 7, 50).get(0);
        EventDetail detail = service.detail(1L);

        assertThat(item.grade()).isEqualTo(detail.grade());
        assertThat(item.confidenceLabel()).isEqualTo(detail.confidenceLabel());
        assertThat(item.multiAgentCompleted()).isEqualTo(detail.multiAgentCompleted());
    }

    /** 분석 전 기사에는 "ERP·계약 영향 분석"을 실행할 수 없고 사유가 함께 내려간다. */
    @Test
    void erpImpactIsBlockedWithReasonWhenAnalysisMissing() {
        RawEvent event = newsEvent(1L, "CL");
        when(rawEventRepository.findById(1L)).thenReturn(Optional.of(event));

        EventDetail detail = service.detail(1L);

        assertThat(detail.erpImpactAvailable()).isFalse();
        assertThat(detail.erpImpactBlockedReason()).contains("AI 분석");
    }

    /** 공급망 무관 판정(NOT_RELEVANT) 기사도 막는다 — 돌려도 ERP 노드가 통째로 비어 나온다. */
    @Test
    void erpImpactIsBlockedForNotRelevantNews() {
        Analysis analysis = Analysis.pending(
                1L, 1L, "Chile lithium mine strike", "lithium 본문", "GDELT", "CL", null);
        analysis.markCompleted("PRODUCTION", null, "NORMAL", 20.0,
                "NOT_RELEVANT", "severity-rule-v1", false);
        stubDetail(newsEvent(1L, "CL"), analysis);

        EventDetail detail = service.detail(1L);

        assertThat(detail.erpImpactAvailable()).isFalse();
        assertThat(detail.erpImpactBlockedReason()).contains("공급망과 무관");
    }

    /** 자재가 특정되지 않은 기사는 목록에서 제외한다 — 트리아지가 통과시킨 무관 기사 필터. */
    @Test
    void excludesNewsWithoutIdentifiableMaterial() {
        RawEvent unrelated = newsEvent(2L, "US", "Local council debates parking fees", "본문");
        when(rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(any(), any()))
                .thenReturn(List.of(unrelated));

        assertThat(service.list(null, null, null, 7, 50)).isEmpty();
    }

    private void stubList(RawEvent event, Analysis analysis) {
        linkAnalysis(event, analysis);
        when(rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(any(), any()))
                .thenReturn(List.of(event));
        when(analysisRepository.findAllById(any())).thenReturn(List.of(analysis));
    }

    private void stubDetail(
            RawEvent event, Analysis analysis, ProcurementRiskDto.Assessment... assessments) {
        linkAnalysis(event, analysis);
        when(rawEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));
        List<ProcurementRiskDto.Assessment> rows =
                Arrays.stream(assessments).filter(Objects::nonNull).toList();
        when(procurementRiskRepository.findMaterialAssessments(analysis.getAnalysisId()))
                .thenReturn(rows);
        when(procurementRiskRepository.findLatestAttemptAt(analysis.getAnalysisId()))
                .thenReturn(rows.isEmpty() ? Optional.empty() : Optional.of(OffsetDateTime.now()));
    }

    private static ProcurementRiskDto.Assessment assessment(
            BigDecimal erpExposureScore, String level, BigDecimal score) {
        return assessment("MAT-LI-CARB", erpExposureScore, level, score);
    }

    /** {@code erpExposureScore}가 null이면 KG 게이트 조기 종료 행(유효하지 않은 평가)을 뜻한다. */
    private static ProcurementRiskDto.Assessment assessment(
            String erpMaterialId, BigDecimal erpExposureScore, String level, BigDecimal score) {
        return new ProcurementRiskDto.Assessment(
                UUID.randomUUID(), UUID.randomUUID(), "news-1", 1L, erpMaterialId, "SUP-CHL-01",
                "LITHIUM", "PRODUCTION", OffsetDateTime.now(), BigDecimal.valueOf(54), "NORMAL",
                erpExposureScore, erpExposureScore == null ? null : BigDecimal.valueOf(30),
                score, level, List.of("근거"), Map.of(), Map.of(),
                "procurement-risk-v1", false, true, false, false, OffsetDateTime.now());
    }

    private static RawEvent newsEvent(long id, String country) {
        return newsEvent(id, country, "Chile lithium mine strike", "lithium production halted");
    }

    private static RawEvent newsEvent(long id, String country, String title, String content) {
        RawEvent event = RawEvent.of(
                "GDELT", "NEWS", "GDELT-" + id, "hash-" + id, title, content,
                "https://example.com/news/" + id, country, -7.2, null);
        setField(event, "id", id);
        return event;
    }

    private static Analysis completed(String country, String severity, Double score) {
        Analysis analysis = Analysis.pending(
                1L, 1L, "Chile lithium mine strike", "lithium production halted",
                "GDELT", country, "https://example.com/news");
        analysis.markCompleted("PRODUCTION", null, severity, score == null ? 0.0 : score,
                "BASE_SCORE_ONLY", "severity-rule-v1", false);
        return analysis;
    }

    private static void linkAnalysis(RawEvent event, Analysis analysis) {
        event.markTriggeredAnalysis(analysis.getAnalysisId());
    }

    /** id는 DB가 채우는 값이라 생성자로 넣을 수 없다. 목록·상세가 id로 이어지는지 보려면 필요하다. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

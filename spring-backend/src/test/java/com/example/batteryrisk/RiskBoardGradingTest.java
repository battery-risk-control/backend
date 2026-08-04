package com.example.batteryrisk;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.RiskEventDto.RiskBoardItem;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.AnalysisSupplierRecommendationRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import com.example.batteryrisk.service.RiskEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공개 리스크 지도의 <b>등급·신뢰도 판정</b> 회귀 테스트.
 *
 * <p>지도와 뉴스 속보가 같은 뉴스를 다르게 부르면 화면끼리 모순된다 — 실제로 지도는 severity와
 * {@code mock}/{@code confidence}로, 속보는 파이프라인 도달 단계로 판정하던 시기가 있어
 * "리튬 [주의] [참고]"처럼 멀티에이전트를 안 거쳤는데 등급이 붙는 조합이 화면에 떴다.
 *
 * <p><b>2026-08-03에 지도의 모집단이 좁아졌다.</b> 이제 지도에는 <b>완결 NEWS 사건</b>만 오른다
 * ({@code NewsEventSql}). 그래서 등급은 항상 그 뉴스의 완결 브리핑에서 오고, 신뢰도 배지는
 * 항상 "확정"이다 — 외부신호(severity)로 등급을 채우던 폴백과 그때 붙던 "경고"·"참고" 배지는
 * 지도에서 사라졌다. 그쪽 판정 자체는 없어진 게 아니라 리스크 모니터링 화면에 남아 있고,
 * {@code newsConfidenceLabel}의 회귀는 뉴스 속보 테스트가 계속 지킨다.
 *
 * <p>모집단을 좁힌 이유는 화면 간 숫자가 갈렸기 때문이다 — 같은 시점에 지도는 주의 3건,
 * 주요 알림은 2건을 보여줬다. 지도만 {@code (국가, 자재)}로 접고 목록은 제목으로 접은 탓이다.
 */
class RiskBoardGradingTest {
    private AnalysisRepository analysisRepository;
    private ProcurementRiskRepository procurementRiskRepository;
    private RawEventRepository rawEventRepository;
    private AiBriefingRepository aiBriefingRepository;
    private RiskEventService service;

    @BeforeEach
    void setUp() {
        analysisRepository = mock(AnalysisRepository.class);
        procurementRiskRepository = mock(ProcurementRiskRepository.class);
        rawEventRepository = mock(RawEventRepository.class);
        when(procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(any())).thenReturn(Map.of());
        when(rawEventRepository.findByTriggeredAnalysisIdIn(any())).thenReturn(List.of());
        aiBriefingRepository = mock(AiBriefingRepository.class);
        when(aiBriefingRepository.findCompletedNewsBriefingsByAnalysisIds(any())).thenReturn(Map.of());
        service = new RiskEventService(
                analysisRepository,
                mock(AnalysisSupplierRecommendationRepository.class),
                rawEventRepository,
                procurementRiskRepository,
                aiBriefingRepository);
    }

    /** 완결 브리핑의 종합 등급이 곧 마커의 등급이고, 배지는 "확정"이다. */
    @Test
    void completedNewsBriefingDrivesGradeAndMarksConfirmed() {
        Analysis analysis = completed("CL", "LITHIUM", "NORMAL", 10.0);
        // 외부신호는 NORMAL인데 브리핑은 CRITICAL — 브리핑이 이겨야 한다.
        //
        // 등급의 출처는 **이 뉴스의 완결된 브리핑**이다. 종합 평가만 보면 계약·자재 화면이 이
        // 뉴스를 외부신호로 끌어다 쓴 실행까지 뉴스 등급으로 둔갑한다.
        stub(analysis);
        stubBriefings(Map.of(analysis.getAnalysisId(), ref("CRITICAL")));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.grade()).isEqualTo("심각");
        assertThat(item.confidenceLabel()).isEqualTo("확정");
    }

    /**
     * 외부신호 점수가 아무리 높아도 완결 브리핑이 없으면 마커를 그리지 않는다.
     *
     * <p>예전에는 여기서 severity로 등급을 채우고 배지만 "경고"로 낮췄다. 그 폴백 때문에 지도에는
     * 뜨는데 목록·알림에는 없는 사건이 생겼다 — 두 화면이 같은 것을 세지 않게 된 원인이다.
     */
    @Test
    void analysisWithoutCompletedNewsBriefingIsNotShown() {
        stub(completed("CL", "LITHIUM", "CRITICAL", 85.0));

        // 마커가 하나도 없으면 공개 화면이 빈 지도가 되지 않도록 placeholder로 폴백한다.
        assertThat(service.riskBoard())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.riskEventId()).startsWith("RISK-"));
    }

    /**
     * 같은 분석이 후보에 두 번 들어와도 마커는 하나다.
     *
     * <p>사건 중복 제거는 공통 사건 키로 SQL이 하지만, 한 분석에 수집 이벤트가 둘 이상 붙으면
     * 조인 결과가 늘어날 수 있어 서비스에도 방어선을 둔다.
     */
    @Test
    void collapsesRepeatedAnalysisIntoOneMarker() {
        Analysis chile = completed("CL", "LITHIUM", "CRITICAL", 85.0);
        Analysis australia = completed("AU", "LITHIUM", "NORMAL", 10.0);
        stub(chile, chile, australia);
        stubBriefings(Map.of(
                chile.getAnalysisId(), ref("CRITICAL"),
                australia.getAnalysisId(), ref("WARNING")));

        List<RiskBoardItem> board = service.riskBoard();

        assertThat(board).hasSize(2);
        assertThat(board).extracting(RiskBoardItem::countryName).containsExactly("칠레", "호주");
        assertThat(board.get(0).grade()).isEqualTo("심각");
        assertThat(board.get(1).grade()).isEqualTo("주의");
    }

    /** 좌표 표에 있는 국가는 마커 좌표가 채워진다(없으면 프론트가 마커에서 제외한다). */
    @Test
    void attachesCoordinatesForKnownCountries() {
        Analysis analysis = completed("CL", "LITHIUM", "NORMAL", 10.0);
        stub(analysis);
        stubBriefings(Map.of(analysis.getAnalysisId(), ref("WARNING")));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.countryName()).isEqualTo("칠레");
        assertThat(item.coordinates()).isNotNull();
        assertThat(item.material()).isEqualTo("리튬");
    }

    /** 브리핑 id는 확정과 항상 짝을 이룬다 — 확정인데 null이면 화면이 브리핑을 열 수 없다. */
    @Test
    void confirmedMarkerCarriesBriefingId() {
        Analysis analysis = completed("CL", "LITHIUM", "NORMAL", 10.0);
        UUID briefingId = UUID.randomUUID();
        stub(analysis);
        stubBriefings(Map.of(
                analysis.getAnalysisId(),
                new AiBriefingRepository.NewsBriefingRef(briefingId, "WARNING")));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.confidenceLabel()).isEqualTo("확정");
        assertThat(item.briefingId()).isEqualTo(briefingId);
    }

    private void stub(Analysis... analyses) {
        when(analysisRepository.findRiskBoardCandidates(any())).thenReturn(List.of(analyses));
    }

    private void stubBriefings(Map<UUID, AiBriefingRepository.NewsBriefingRef> briefings) {
        when(aiBriefingRepository.findCompletedNewsBriefingsByAnalysisIds(any())).thenReturn(briefings);
    }

    private static AiBriefingRepository.NewsBriefingRef ref(String riskLevel) {
        return new AiBriefingRepository.NewsBriefingRef(UUID.randomUUID(), riskLevel);
    }

    private static Analysis completed(
            String country, String material, String severity, Double score) {
        Analysis analysis = Analysis.pending(
                1L, 1L, "Chile lithium mine strike", "본문", "GDELT", country,
                "https://example.com/news/" + UUID.randomUUID());
        analysis.markCompleted(
                "생산", null, severity, score == null ? 0.0 : score,
                "BASE_SCORE_ONLY", "severity-rule-v1", false);
        analysis.attachSupplierRecommendation(material, List.of());
        return analysis;
    }
}

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
 * <p>판정 규칙(둘 공통):
 * <ul>
 *   <li><b>확정</b> — 멀티에이전트 종합 등급이 있는 분석. 등급도 그 값을 쓴다.</li>
 *   <li><b>경고</b> — 외부신호 점수가 기준(67) 이상이지만 멀티에이전트 미완.</li>
 *   <li><b>참고</b> — 그 외.</li>
 * </ul>
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

    /** 멀티에이전트 종합 등급이 있으면 그 값이 등급이 되고 배지는 "확정"이다. */
    @Test
    void multiAgentResultDrivesGradeAndMarksConfirmed() {
        Analysis analysis = completed("CL", "LITHIUM", "NORMAL", 10.0);
        stub(analysis);
        // 외부신호는 NORMAL인데 멀티에이전트는 CRITICAL — 멀티에이전트가 이겨야 한다.
        //
        // 등급의 출처는 **이 뉴스의 완결된 브리핑**이다(2026-08-03). 종합 평가만 보면 계약·자재
        // 화면이 이 뉴스를 외부신호로 끌어다 쓴 실행까지 뉴스 등급으로 둔갑한다.
        when(aiBriefingRepository.findCompletedNewsBriefingsByAnalysisIds(any()))
                .thenReturn(Map.of(
                        analysis.getAnalysisId(),
                        new AiBriefingRepository.NewsBriefingRef(UUID.randomUUID(), "CRITICAL")));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.grade()).isEqualTo("심각");
        assertThat(item.confidenceLabel()).isEqualTo("확정");
    }

    /**
     * 멀티에이전트 미완이면 외부신호 등급으로 마커를 그리되 배지는 "경고"다.
     * 마커는 색이 있어야 그려지므로 등급 자체는 비울 수 없고, 검증 수준은 배지가 말한다.
     */
    @Test
    void externalSignalAboveThresholdIsMarkedWarning() {
        stub(completed("CL", "LITHIUM", "CRITICAL", 85.0));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.grade()).isEqualTo("심각");
        assertThat(item.confidenceLabel()).isEqualTo("경고");
    }

    /** 기준(67) 미만이면 "참고" — 점수가 있어도 근거가 약하다. */
    @Test
    void externalSignalBelowThresholdIsMarkedReference() {
        stub(completed("CL", "LITHIUM", "NORMAL", 54.1));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.grade()).isEqualTo("정상");
        assertThat(item.confidenceLabel()).isEqualTo("참고");
    }

    /** 점수가 아예 없으면 "참고". */
    @Test
    void analysisWithoutAnyScoreIsMarkedReference() {
        stub(completed("CL", "LITHIUM", "NORMAL", null));

        assertThat(service.riskBoard().get(0).confidenceLabel()).isEqualTo("참고");
    }

    /** 같은 (국가, 자재)는 한 마커로 접는다 — 같은 좌표에 겹쳐 찍히면 지도를 읽을 수 없다. */
    @Test
    void collapsesDuplicateCountryMaterialPairsIntoOneMarker() {
        stub(
                completed("CL", "LITHIUM", "CRITICAL", 85.0),
                completed("CL", "LITHIUM", "NORMAL", 10.0),
                completed("AU", "LITHIUM", "NORMAL", 10.0));

        List<RiskBoardItem> board = service.riskBoard();

        assertThat(board).hasSize(2);
        assertThat(board).extracting(RiskBoardItem::countryName)
                .containsExactly("칠레", "호주");
        // 먼저 온 쪽(최신)이 남는다 — 뒤의 NORMAL이 덮어쓰면 심각이 지도에서 사라진다.
        assertThat(board.get(0).grade()).isEqualTo("심각");
    }

    /** 좌표 표에 있는 국가는 마커 좌표가 채워진다(없으면 프론트가 마커에서 제외한다). */
    @Test
    void attachesCoordinatesForKnownCountries() {
        stub(completed("CL", "LITHIUM", "NORMAL", 10.0));

        RiskBoardItem item = service.riskBoard().get(0);

        assertThat(item.countryName()).isEqualTo("칠레");
        assertThat(item.coordinates()).isNotNull();
        assertThat(item.material()).isEqualTo("리튬");
    }

    private void stub(Analysis... analyses) {
        when(analysisRepository.findRiskBoardCandidates(any())).thenReturn(List.of(analyses));
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

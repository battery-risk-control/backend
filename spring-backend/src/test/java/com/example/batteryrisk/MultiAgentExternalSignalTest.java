package com.example.batteryrisk;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.ProcurementRiskDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.service.ErpService;
import com.example.batteryrisk.service.KgResolverService;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import com.example.batteryrisk.service.MultiAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 외부신호(가중치 0.35)가 멀티에이전트까지 실제로 도달하는지 지키는 회귀 테스트.
 *
 * <p>이 연결이 끊기면 FastAPI {@code risk_node}가 {@code normalize_score(None)} → 0으로 처리해
 * 종합 점수가 최대 35점 낮게 나오는데, 응답은 200이고 로그도 남지 않아 조용히 틀린다.
 * 그래서 "점수가 흘러갔는가"를 값으로 직접 확인한다.
 */
class MultiAgentExternalSignalTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-07-31T12:00:00+09:00");

    /**
     * 이음매 테스트용 FastAPI 성공 응답.
     *
     * <p>KG 리졸버 배선(#8)으로 추가된 {@code kg*} 필드를 일부러 포함했다. Spring
     * {@code MultiAgentDto.Response}에는 대응 필드가 없으므로, 이 응답이 파싱되면
     * "모르는 필드는 무시된다"가 함께 검증된다.
     */
    private static final String FASTAPI_RESPONSE = """
            {
              "success": true,
              "data": {
                "newsId": "NEWS-001",
                "impactDomainFinal": "production",
                "kgMatched": true,
                "kgShortageDetected": false,
                "kgAffectedSuppliers": ["SUP-CHL-01"],
                "kgAffectedContractIds": ["CTR-001"],
                "kgEvidencePaths": [],
                "procurementRiskLevel": "critical",
                "procurementRiskScore": 72,
                "riskReasons": ["외부 공급망 신호 점수: 82"],
                "erpAssessment": {},
                "erpReassessment": {},
                "contractAssessment": {},
                "contractFindings": [],
                "recommendedActions": [],
                "briefing": "구매팀 브리핑입니다.",
                "llmUsed": false,
                "llmError": null,
                "reviewPassed": true,
                "warnings": []
              },
              "timestamp": "2026-07-31T03:00:00Z"
            }
            """;

    private ErpService erpService;
    private MultiAgentService multiAgentService;
    private AnalysisRepository analysisRepository;
    private ProcurementRiskRepository procurementRiskRepository;
    private KgResolverService kgResolverService;
    private ErpRepository erpRepository;
    private MultiAgentOrchestrationService service;

    @BeforeEach
    void setUp() {
        erpService = mock(ErpService.class);
        multiAgentService = mock(MultiAgentService.class);
        analysisRepository = mock(AnalysisRepository.class);
        procurementRiskRepository = mock(ProcurementRiskRepository.class);
        kgResolverService = mock(KgResolverService.class);
        erpRepository = mock(ErpRepository.class);
        when(kgResolverService.resolve(any(), any()))
                .thenReturn(KgResolverService.KgResolveResult.NOT_MATCHED);
        service = new MultiAgentOrchestrationService(
                erpService, multiAgentService, erpRepository,
                analysisRepository, procurementRiskRepository, kgResolverService);
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(multiAgentService.generate(any())).thenReturn(stubResponse());
    }

    @Test
    void analysisIdFillsExternalSignalFromStoredAnalysis() {
        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        service.generate(request(analysis.getAnalysisId(), null, null));

        MultiAgentDto.Request sent = captureFastApiRequest();
        // analyses.severity_score(81.7, double) → FastAPI 계약(0~100 int) 반올림
        assertThat(sent.externalSignalScore()).isEqualTo(82);
        // FastAPI RiskLevel은 소문자다. 대문자로 보내면 매칭이 조용히 실패한다.
        assertThat(sent.externalSignalLevel()).isEqualTo("critical");
    }

    /**
     * 이음매 테스트 — {@link #analysisIdFillsExternalSignalFromStoredAnalysis()}는 MultiAgentService를
     * mock으로 잡아서 <b>JSON 직렬화가 아예 돌지 않는다</b>. 그래서 {@code @JsonProperty} 이름이
     * FastAPI 계약과 어긋나도 그 테스트는 통과한다.
     *
     * <p>여기서는 실제 MultiAgentService + RestClient를 태워 <b>실제로 전송되는 본문</b>을 검사한다.
     * FastAPI {@code MultiAgentBriefingRequest}는 {@code extra="forbid"}라 필드명이 틀리면 422가 되고,
     * 값 표기가 소문자가 아니면 RiskLevel 검증에 걸린다.
     */
    @Test
    void externalSignalIsSerializedWithFastApiFieldNames() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MultiAgentOrchestrationService seamService = new MultiAgentOrchestrationService(
                erpService, new MultiAgentService(builder.build()),
                mock(ErpRepository.class), analysisRepository, procurementRiskRepository,
                kgResolverService);

        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        server.expect(once(), requestTo(
                        "http://localhost:8000/api/v1/internal/multi-agent/briefings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "external_signal_score": 82,
                          "external_signal_level": "critical"
                        }
                        """))
                .andRespond(withSuccess(FASTAPI_RESPONSE, MediaType.APPLICATION_JSON));

        MultiAgentDto.Response response =
                seamService.generate(request(analysis.getAnalysisId(), null, null));

        server.verify();
        // kg* 필드가 늘어나도 기존 응답 파싱이 깨지지 않아야 한다.
        assertThat(response.procurementRiskScore()).isEqualTo(72);
    }

    /**
     * KG가 재고부족을 확정하고 연결된 아웃바운드 계약을 돌려주면, Spring이 그 외부ID를
     * 내부 PK로 리졸브해서 FastAPI 요청에 실어 보내는지 확인한다.
     */
    @Test
    void resolvesOutboundContractWhenKgConfirmsShortage() {
        when(kgResolverService.resolve(eq("CL"), eq("LITHIUM")))
                .thenReturn(new KgResolverService.KgResolveResult(
                        true, "SUP-CHL-01", List.of("CTR-OUT-005")));
        when(erpRepository.findTopOutboundContractsByExposure(List.of("CTR-OUT-005"), 5))
                .thenReturn(List.of(
                        new ErpRepository.RankedOutboundContract(501L, 601L, 701L)));

        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        service.generate(request(analysis.getAnalysisId(), null, null));

        MultiAgentDto.Request sent = captureFastApiRequest();
        assertThat(sent.outboundContracts())
                .containsExactly(new MultiAgentDto.OutboundContractRef(501L, 601L, 701L));
        assertThat(sent.outboundContractsTotalMatched()).isEqualTo(1);
    }

    /**
     * kg_service가 재무 노출도 상위 5건보다 많은 아웃바운드 계약을 돌려주면(2026-07-31 실측
     * 콩고+코발트 21건), 상위 5건만 FastAPI 요청에 싣고 원래 전체 매칭 건수는 별도로
     * totalMatched에 남긴다.
     */
    @Test
    void resolvesTopFiveOutboundContractsByExposureWhenManyMatch() {
        List<String> erpIds = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "CTR-OUT-%03d".formatted(i))
                .toList();
        when(kgResolverService.resolve(eq("CL"), eq("LITHIUM")))
                .thenReturn(new KgResolverService.KgResolveResult(true, "SUP-CHL-01", erpIds));
        List<ErpRepository.RankedOutboundContract> top5 = List.of(
                new ErpRepository.RankedOutboundContract(1L, 11L, 21L),
                new ErpRepository.RankedOutboundContract(2L, 12L, 22L),
                new ErpRepository.RankedOutboundContract(3L, 13L, 23L),
                new ErpRepository.RankedOutboundContract(4L, 14L, 24L),
                new ErpRepository.RankedOutboundContract(5L, 15L, 25L));
        when(erpRepository.findTopOutboundContractsByExposure(erpIds, 5)).thenReturn(top5);

        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        service.generate(request(analysis.getAnalysisId(), null, null));

        MultiAgentDto.Request sent = captureFastApiRequest();
        assertThat(sent.outboundContracts()).hasSize(5);
        assertThat(sent.outboundContractsTotalMatched()).isEqualTo(21);
    }

    /**
     * kg_service가 재고부족을 확정 못 하거나(매칭없음/재고충분) 아웃바운드 계약이 아예 없는
     * 경우는 부가 기능이 조용히 비어야 한다 — 예외를 던지면 안 되고, FastAPI 요청도 그대로
     * 나가야 한다(기존처럼 아웃바운드 없이).
     */
    @Test
    void outboundContractsEmptyWhenKgDoesNotConfirmShortage() {
        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        service.generate(request(analysis.getAnalysisId(), null, null));

        MultiAgentDto.Request sent = captureFastApiRequest();
        assertThat(sent.outboundContracts()).isEmpty();
        assertThat(sent.outboundContractsTotalMatched()).isZero();
    }

    /**
     * 외부ID→내부PK 리졸브가 실패해도(아직 안 올라온 계약 등) 나머지 요청은 정상 진행돼야 한다 —
     * KgResolverService와 같은 "부가 기능은 실패해도 흡수" 방침. kg_service는 매칭됐다고 했으므로
     * totalMatched는 그대로 남지만, 리졸브된 계약이 없어 outboundContracts는 비어야 한다.
     */
    @Test
    void outboundContractsEmptyWhenResolveFails() {
        when(kgResolverService.resolve(eq("CL"), eq("LITHIUM")))
                .thenReturn(new KgResolverService.KgResolveResult(
                        true, "SUP-CHL-01", List.of("CTR-OUT-999")));
        when(erpRepository.findTopOutboundContractsByExposure(List.of("CTR-OUT-999"), 5))
                .thenReturn(List.of());

        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        service.generate(request(analysis.getAnalysisId(), null, null));

        MultiAgentDto.Request sent = captureFastApiRequest();
        assertThat(sent.outboundContracts()).isEmpty();
        assertThat(sent.outboundContractsTotalMatched()).isEqualTo(1);
    }

    @Test
    void requestBodyStillWorksWithoutAnalysisId() {
        service.generate(request(null, "warning", 55));

        MultiAgentDto.Request sent = captureFastApiRequest();
        assertThat(sent.externalSignalScore()).isEqualTo(55);
        assertThat(sent.externalSignalLevel()).isEqualTo("warning");
        verify(analysisRepository, never()).findById(any());
    }

    /**
     * 세부 점수 3개가 전용 컬럼으로 저장되는지 확인한다.
     *
     * <p>FastAPI 응답에서 이 값들은 {@code riskReasons} 문장과 {@code erpAssessment}/
     * {@code contractAssessment} Map 안에 <b>snake_case 키</b>로 묻혀 있다. FastAPI 모델의
     * alias_generator는 필드 이름만 camelCase로 바꾸고 dict 내용은 건드리지 않기 때문인데,
     * 이걸 camelCase로 착각해 꺼내면 전부 null로 저장되고도 아무 에러가 안 난다.
     */
    @Test
    void persistsSubScoresIntoDedicatedColumns() {
        Analysis analysis = completedAnalysis("CRITICAL", 81.7, "BASE_SCORE_ONLY");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        MultiAgentDto.Response response = service.generate(request(analysis.getAnalysisId(), null, null));

        ArgumentCaptor<ProcurementRiskDto.Assessment> captor =
                ArgumentCaptor.forClass(ProcurementRiskDto.Assessment.class);
        verify(procurementRiskRepository).save(captor.capture());
        ProcurementRiskDto.Assessment saved = captor.getValue();

        assertThat(saved.externalSignalScore()).isEqualByComparingTo("82");
        assertThat(saved.erpExposureScore()).isEqualByComparingTo("45");
        assertThat(saved.contractGapScore()).isEqualByComparingTo("30");
        assertThat(saved.procurementRiskScore()).isEqualByComparingTo("72");
        // DB CHECK 제약은 대문자만 받는데 FastAPI는 소문자로 준다.
        assertThat(saved.procurementRiskLevel()).isEqualTo("CRITICAL");
        // 가중치를 바꾸고 이 버전을 안 올리면 과거 점수를 해석할 수 없게 된다.
        assertThat(saved.weightVersion()).isEqualTo("procurement-risk-v1");
        assertThat(saved.analysisId()).isEqualTo(analysis.getAnalysisId());
        assertThat(saved.materialCategory()).isEqualTo("LITHIUM");

        // 조원 인계문서 A — 브리핑 산출물이 응답으로만 나가고 사라지던 문제. use_llm=false에서도
        // 템플릿 조립본이 그대로 나와야 한다. V29부터 본문의 저장처는 이 테이블이 아니라
        // ai_briefings다(AiBriefingService.generate / recordAutoGenerated). 여기서는 본문이
        // 응답에 실려 그 저장 경로까지 전달되는지만 확인한다.
        assertThat(response.briefing()).isEqualTo("구매팀 브리핑입니다.");
        assertThat(response.recommendedActions()).isEmpty();
        assertThat(response.contractFindings()).isEmpty();
        assertThat(response.warnings()).isEmpty();

        // 호출자가 나중에 다시 꺼내볼 수 있도록 응답에 id가 실려 나가야 한다.
        assertThat(response.assessmentId()).isEqualTo(saved.assessmentId());
    }

    @Test
    void notRelevantAnalysisIsRejectedBeforeCallingFastApi() {
        Analysis analysis = completedAnalysis("NORMAL", 0.0, "NOT_RELEVANT");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        assertThatThrownBy(() -> service.generate(request(analysis.getAnalysisId(), null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_NOT_SUPPLY_CHAIN_RELEVANT));

        // 공급망 무관 기사에 LangGraph 노드와 LLM을 태우지 않는 것이 이 차단의 목적이다.
        verify(multiAgentService, never()).generate(any());
    }

    @Test
    void missingBothAnalysisIdAndExternalSignalIsRejected() {
        assertThatThrownBy(() -> service.generate(request(null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_SIGNAL_REQUIRED));
    }

    @Test
    void analysisWithoutScoreIsRejected() {
        // PENDING — 아직 채점 전이라 severity_score가 없다.
        Analysis analysis = Analysis.pending(
                2L, 3L, "제목", "본문", "GDELT", "CL", "https://example.com/news/2");
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));

        assertThatThrownBy(() -> service.generate(request(analysis.getAnalysisId(), null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_NOT_SCORED));
    }

    /**
     * 조원 인계문서(C) — 구매 리스크 자동 축적 스케줄러. analyses는 대분류(material_category)만
     * 채우고 material_id는 비워두므로, 스케줄러가 대분류를 활성 ERP 자재로 펼쳐야 한다.
     * LITHIUM은 자재가 2개(탄산/수산화)라 분석 1건이 호출 2건이 된다.
     */
    @Test
    void scoreNewAnalysesExpandsMaterialCategoryToMultipleErpMaterials() {
        Analysis analysis = completedAnalysis("WARNING", 70.0, "BASE_SCORE_ONLY");
        when(procurementRiskRepository.findUnscoredAnalysisIds(10))
                .thenReturn(List.of(analysis.getAnalysisId()));
        when(analysisRepository.findAllById(List.of(analysis.getAnalysisId())))
                .thenReturn(List.of(analysis));
        // generate()가 analysisId 경로로 외부신호를 읽으므로 findById도 배선해야 한다.
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));
        when(erpRepository.findActiveErpMaterialIds("LITHIUM"))
                .thenReturn(List.of("MAT-LI-CARB", "MAT-LI-OH"));

        int saved = service.scoreNewAnalyses(10);

        assertThat(saved).isEqualTo(2);
        verify(multiAgentService, org.mockito.Mockito.times(2)).generate(any());
        verify(procurementRiskRepository, org.mockito.Mockito.times(2)).save(any());
    }

    /** 자재 단위 try/catch — 한 건이 실패해도 나머지 자재는 계속 처리돼야 한다. */
    @Test
    void scoreNewAnalysesIsolatesPartialFailure() {
        Analysis analysis = completedAnalysis("WARNING", 70.0, "BASE_SCORE_ONLY");
        when(procurementRiskRepository.findUnscoredAnalysisIds(10))
                .thenReturn(List.of(analysis.getAnalysisId()));
        when(analysisRepository.findAllById(List.of(analysis.getAnalysisId())))
                .thenReturn(List.of(analysis));
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));
        when(erpRepository.findActiveErpMaterialIds("LITHIUM"))
                .thenReturn(List.of("MAT-LI-CARB", "MAT-LI-OH"));
        when(erpService.buildContext(any()))
                .thenThrow(new RuntimeException("ERP Context 없음(MAT-LI-CARB)"))
                .thenReturn(erpContext());

        int saved = service.scoreNewAnalyses(10);

        assertThat(saved).isEqualTo(1);
        verify(procurementRiskRepository, org.mockito.Mockito.times(1)).save(any());
    }

    /** 대상이 0건일 때 FastAPI를 호출하지 않아야 한다(비용 통제). */
    @Test
    void scoreNewAnalysesSkipsFastApiWhenNoTargets() {
        when(procurementRiskRepository.findUnscoredAnalysisIds(10)).thenReturn(List.of());

        int saved = service.scoreNewAnalyses(10);

        assertThat(saved).isZero();
        verifyNoInteractions(multiAgentService);
    }

    private MultiAgentDto.Request captureFastApiRequest() {
        ArgumentCaptor<MultiAgentDto.Request> captor =
                ArgumentCaptor.forClass(MultiAgentDto.Request.class);
        verify(multiAgentService).generate(captor.capture());
        return captor.getValue();
    }

    private static MultiAgentDto.GenerateRequest request(
            UUID analysisId, String externalSignalLevel, Integer externalSignalScore) {
        return new MultiAgentDto.GenerateRequest(
                "NEWS-001", analysisId, "칠레 리튬 광산 파업", "본문", "요약",
                "production", "production",
                externalSignalLevel, externalSignalScore,
                "MAT-LI-CARB", "SUP-CHL-01", "CL", AS_OF, false);
    }

    private static Analysis completedAnalysis(String severity, double score, String reasonCodes) {
        Analysis analysis = Analysis.pending(
                2L, 3L, "칠레 리튬 광산 파업", "본문", "GDELT", "CL", "https://example.com/news/1");
        analysis.markCompleted(
                "생산", null, severity, score, reasonCodes, "severity-rule-v0.2-realtime", false);
        analysis.attachSupplierRecommendation("LITHIUM", List.of());
        return analysis;
    }

    /**
     * FastAPI 멀티에이전트 응답 스텁. 세부 점수는 실제와 같이 <b>snake_case 키</b>로 Map 안에 둔다 —
     * risk_node와 soojung_adapter가 쓰는 키가 그렇다.
     */
    private static MultiAgentDto.Response stubResponse() {
        return new MultiAgentDto.Response(
                null,                       // assessmentId — FastAPI는 모른다. Spring이 저장 후 채운다
                "NEWS-001",
                "production",
                "critical",                 // FastAPI는 소문자로 준다
                72,
                List.of("외부 공급망 신호 점수: 82", "ERP 내부 노출도 점수: 45", "계약 보호 공백 점수: 30"),
                Map.of("erp_exposure_score", 45, "stockout_before_eta", false),
                Map.of(),
                Map.of("contract_gap_score", 30),
                List.of(),
                List.of(),
                "구매팀 브리핑입니다.",
                false,
                null,
                true,
                List.of());
    }

    private static ErpDto.ContextResponse erpContext() {
        return new ErpDto.ContextResponse(
                2L, "MAT-LI-CARB", "Lithium Carbonate", "KG",
                bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("36000"),
                bd("1000"), bd("15000"), BigDecimal.ZERO,
                bd("36"), bd("15"), LocalDate.parse("2026-08-30"), 8,
                BigDecimal.ZERO, bd("134900"), bd("0.45"), "CONFIRMED", "APPROVED",
                "ACTIVE", "NO", 3L, "SUP-CHL-01", 2L, "CTR-001", "VALID",
                AS_OF, "ERP_MOCK", true);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

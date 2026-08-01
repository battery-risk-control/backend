package com.example.batteryrisk;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.AiBriefingDto;
import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import com.example.batteryrisk.dto.RiskMonitoringDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.repository.AiBriefingRepository;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ContractRagRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import com.example.batteryrisk.service.AiBriefingService;
import com.example.batteryrisk.service.ContractRagService;
import com.example.batteryrisk.service.ErpExposureRequestService;
import com.example.batteryrisk.service.ErpService;
import com.example.batteryrisk.service.MaterialRiskService;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import com.example.batteryrisk.service.RiskMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 브리핑 화면 서비스가 지켜야 할 것 세 가지를 고정한다.
 *
 * <ul>
 *   <li><b>앞 화면의 판정을 그대로 물려받는다</b> — 리스크 모니터링이 "공급망 무관"이라 버튼을 막아둔
 *       기사를 이 화면에서 돌리면 안 되고, 사유 문구도 같아야 한다. 판정이 갈라지면 앞 화면은
 *       "가능"이라 표시하는데 눌렀을 때 422가 나거나 그 반대가 된다.</li>
 *   <li><b>멀티에이전트 응답이 화면 칸으로 정확히 펼쳐진다</b> — 사진의 "ERP 노출 근거"·"계약서에서
 *       확인된 근거"·"분석 근거 4칸"·"검증 메타데이터"는 전부 응답 Map에서 꺼낸 값이다. 키 하나가
 *       어긋나면 그 줄만 조용히 비어 화면에서 알아채기 어렵다.</li>
 *   <li><b>조기 종료를 정상 판정으로 읽지 않는다</b> — KG 게이트에서 끊긴 실행도 0점·NORMAL로 응답이
 *       오는데, 그것을 "평가 결과 정상"으로 저장하면 평가하지 못한 것을 안전하다고 표기하게 된다.</li>
 * </ul>
 */
class AiBriefingServiceTest {
    private RiskMonitoringService riskMonitoringService;
    private MaterialRiskService materialRiskService;
    private ContractRagService contractRagService;
    private ContractRagRepository contractRagRepository;
    private RawEventRepository rawEventRepository;
    private AnalysisRepository analysisRepository;
    private ErpExposureRequestService erpExposureRequestService;
    private ErpService erpService;
    private MultiAgentOrchestrationService multiAgentOrchestrationService;
    private AiBriefingRepository repository;
    private AiBriefingService service;

    @BeforeEach
    void setUp() {
        riskMonitoringService = mock(RiskMonitoringService.class);
        materialRiskService = mock(MaterialRiskService.class);
        contractRagService = mock(ContractRagService.class);
        contractRagRepository = mock(ContractRagRepository.class);
        rawEventRepository = mock(RawEventRepository.class);
        analysisRepository = mock(AnalysisRepository.class);
        erpExposureRequestService = mock(ErpExposureRequestService.class);
        erpService = mock(ErpService.class);
        multiAgentOrchestrationService = mock(MultiAgentOrchestrationService.class);
        repository = mock(AiBriefingRepository.class);
        service = new AiBriefingService(
                riskMonitoringService, materialRiskService, contractRagService, contractRagRepository,
                rawEventRepository, analysisRepository, erpExposureRequestService, erpService,
                multiAgentOrchestrationService, repository);
    }

    // ------------------------------------------------------------ 앞 화면 판정 물려받기

    @Test
    void 리스크_모니터링이_막아둔_기사는_같은_사유로_거절한다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenNewsEvent(252L, analysis);
        when(riskMonitoringService.detail(252L)).thenReturn(
                eventDetail(false, "공급망과 무관한 뉴스로 판정되었습니다."));

        AiBriefingDto.Context context = service.context("NEWS", "252");
        assertThat(context.generateAvailable()).isFalse();
        assertThat(context.generateBlockedReason()).isEqualTo("공급망과 무관한 뉴스로 판정되었습니다.");

        assertThatThrownBy(() -> service.generate(
                new AiBriefingDto.GenerateRequest("NEWS", "252", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("공급망과 무관한 뉴스");

        // 막힌 대상은 그래프를 태우지 않는다 — 버튼 한 번이 곧 LLM 비용이다.
        verify(multiAgentOrchestrationService, never()).generate(any());
        verify(repository, never()).save(any(), anyString());
    }

    @Test
    void 자재_대분류에_연결된_ERP_자재가_없으면_생성을_막는다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenNewsEvent(252L, analysis);
        when(riskMonitoringService.detail(252L)).thenReturn(eventDetail(true, null));
        when(erpExposureRequestService.resolveErpTarget("CD", "COBALT")).thenReturn(null);

        AiBriefingDto.Context context = service.context("NEWS", "252");

        assertThat(context.generateAvailable()).isFalse();
        assertThat(context.generateBlockedReason()).contains("COBALT");
    }

    @Test
    void source가_이상하면_400이다() {
        assertThatThrownBy(() -> service.context("SOMETHING", "1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NEWS");
    }

    // ------------------------------------------------------------ 프리필

    @Test
    void 자재에서_넘어오면_ERP_연결_세_칸을_채운다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenMaterial("MAT-CO-SULF", true, null);
        givenExternalSignalNews("COBALT", null, analysis);
        when(erpService.buildContext(any())).thenReturn(erpContext());

        AiBriefingDto.Context context = service.context("MATERIAL", "MAT-CO-SULF");

        assertThat(context.generateAvailable()).isTrue();
        assertThat(context.erpMaterialId()).isEqualTo("MAT-CO-SULF");
        assertThat(context.erpSupplierId()).isEqualTo("SUP-COD-01");
        assertThat(context.erpContractId()).isEqualTo("CTR-010");
        assertThat(context.contractId()).isEqualTo(11L);
        // 분석 대상은 자재명이지만 외부신호는 별개의 저장된 뉴스에서 온다 — 둘을 구분해 보여줘야 한다.
        assertThat(context.subjectTitle()).isEqualTo("황산코발트");
        assertThat(context.sourceHeadline()).isEqualTo("코발트 공급사의 납기 지연");
        assertThat(context.externalSignalLevel()).isEqualTo("WARNING");
        assertThat(context.externalSignalScore()).isEqualByComparingTo("60.0");
    }

    // ------------------------------------------------------------ 응답 → 화면 칸

    @Test
    void 멀티에이전트_응답을_화면_칸으로_펼친다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenMaterial("MAT-CO-SULF", true, null);
        givenExternalSignalNews("COBALT", null, analysis);
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(multiAgentOrchestrationService.generate(any())).thenReturn(compositeResponse());

        AiBriefingDto.BriefingDetail detail = service.generate(
                new AiBriefingDto.GenerateRequest("MATERIAL", "MAT-CO-SULF", null));

        assertThat(detail.composite()).isTrue();
        assertThat(detail.procurementRiskLevel()).isEqualTo("CRITICAL");
        assertThat(detail.procurementRiskScore()).isEqualByComparingTo("70");
        assertThat(detail.recommendedActions()).containsExactly("기존 발주의 납기와 수량을 우선 확인합니다.");

        // "ERP 노출 근거" 한 줄. 의존도만 ERP Context에서 오고 나머지는 erp_assessment에서 온다.
        AiBriefingDto.ErpEvidence evidence = detail.erpEvidence();
        assertThat(evidence.exposureScore()).isEqualByComparingTo("75.0");
        assertThat(evidence.exposureLevel()).isEqualTo("CRITICAL");
        assertThat(evidence.inventoryDays()).isEqualByComparingTo("6.5");
        assertThat(evidence.safetyStockDays()).isEqualByComparingTo("18.0");
        assertThat(evidence.nextInboundEtaDays()).isEqualTo(9);
        assertThat(evidence.expectedSupplyGapDays()).isEqualByComparingTo("2.5");
        assertThat(evidence.supplierDependencyRatio()).isEqualByComparingTo("0.84");

        // 우측 "분석 근거" 4칸.
        AiBriefingDto.EvidenceChain chain = detail.evidenceChain();
        assertThat(chain.externalSignal().level()).isEqualTo("WARNING");
        assertThat(chain.externalSignal().score()).isEqualByComparingTo("60.0");
        assertThat(chain.erpExposure().score()).isEqualByComparingTo("75.0");
        assertThat(chain.contractRag().note()).isEqualTo("1개 조항");
        assertThat(chain.finalRisk().level()).isEqualTo("CRITICAL");

        // "검증 메타데이터" — 계약 ID·페이지는 첫 근거 조항에서 꺼낸다.
        assertThat(detail.verification().reviewPassed()).isTrue();
        assertThat(detail.verification().llmUsed()).isTrue();
        assertThat(detail.verification().contractId()).isEqualTo(11L);
        assertThat(detail.verification().contractPage()).isEqualTo(1);
        assertThat(detail.verification().warningCount()).isZero();

        verify(repository).save(eq(detail), any());
    }

    @Test
    void use_llm을_생략하면_LLM을_켠다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenMaterial("MAT-CO-SULF", true, null);
        givenExternalSignalNews("COBALT", null, analysis);
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(multiAgentOrchestrationService.generate(any())).thenReturn(compositeResponse());

        service.generate(new AiBriefingDto.GenerateRequest("MATERIAL", "MAT-CO-SULF", null));

        ArgumentCaptor<MultiAgentDto.GenerateRequest> captor =
                ArgumentCaptor.forClass(MultiAgentDto.GenerateRequest.class);
        verify(multiAgentOrchestrationService).generate(captor.capture());
        assertThat(captor.getValue().useLlm()).isTrue();
        // 외부신호는 analysisId로 넘긴다 — 화면이 보여준 점수와 종합 점수의 입력이 어긋나지 않게.
        assertThat(captor.getValue().analysisId()).isEqualTo(analysis.getAnalysisId());
    }

    @Test
    void KG_게이트에서_끊긴_실행은_정상_판정으로_읽지_않는다() {
        Analysis analysis = completedAnalysis("COBALT");
        givenMaterial("MAT-CO-SULF", true, null);
        givenExternalSignalNews("COBALT", null, analysis);
        when(erpService.buildContext(any())).thenReturn(erpContext());
        when(multiAgentOrchestrationService.generate(any())).thenReturn(earlyExitResponse());

        AiBriefingDto.BriefingDetail detail = service.generate(
                new AiBriefingDto.GenerateRequest("MATERIAL", "MAT-CO-SULF", false));

        assertThat(detail.composite()).isFalse();
        assertThat(detail.erpEvidence().exposureScore()).isNull();
        // 조기 종료 사유는 risk_reasons로 화면에 그대로 전달돼야 한다.
        assertThat(detail.riskReasons()).contains("공급망 경로 매칭이 없어 조기 종료했습니다.");
    }

    // ------------------------------------------------------------ 준비 helper

    private void givenNewsEvent(long eventId, Analysis analysis) {
        RawEvent event = mock(RawEvent.class);
        when(event.getTitle()).thenReturn("Cobalt supplier delays shipment");
        when(event.getTriggeredAnalysisId()).thenReturn(analysis.getAnalysisId());
        when(rawEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));
    }

    private void givenMaterial(String erpMaterialId, boolean available, String blockedReason) {
        when(materialRiskService.detail(erpMaterialId))
                .thenReturn(materialDetail(erpMaterialId, available, blockedReason));
    }

    private void givenExternalSignalNews(String category, String countryCode, Analysis analysis) {
        when(contractRagRepository.findLatestRelatedNews(category, countryCode))
                .thenReturn(Optional.of(sourceNews(analysis)));
        when(analysisRepository.findById(analysis.getAnalysisId())).thenReturn(Optional.of(analysis));
    }

    private static Analysis completedAnalysis(String materialCategory) {
        Analysis analysis = Analysis.pending(
                1L, 2L, "코발트 공급사의 납기 지연", "본문", "GDELT", "CD", "https://example.test/a");
        analysis.markCompleted("PRODUCTION", 0.8, "warning", 60.0, "SUPPLY_DELAY", "severity-rule-v1", false);
        analysis.attachSupplierRecommendation(materialCategory, List.of());
        analysis.applySignalDetail("납기 지연 요약", -0.42, 18, -6.5);
        return analysis;
    }

    private static RiskMonitoringDto.EventDetail eventDetail(boolean available, String blockedReason) {
        return new RiskMonitoringDto.EventDetail(
                252L, "주의", "잠정", false, "코발트 공급사의 납기 지연",
                "Cobalt supplier delays shipment", true, "납기 지연 요약", "코발트",
                "PRODUCTION", "CD", "콩고민주공화국", null, Instant.now(), "GDELT",
                "https://example.test/a", null, null, available, blockedReason);
    }

    private static MaterialRiskDto.MaterialDetail materialDetail(
            String erpMaterialId, boolean available, String blockedReason) {
        return new MaterialRiskDto.MaterialDetail(
                erpMaterialId, "황산코발트", "COBALT", "심각", "CRITICAL",
                new BigDecimal("75.0"), new BigDecimal("6.5"), new BigDecimal("18.0"),
                new BigDecimal("0.84"), "VALID", null,
                "KG", new BigDecimal("1300"), new BigDecimal("1300"), new BigDecimal("3600"),
                new BigDecimal("200"), null, 9, new BigDecimal("2.5"), Instant.now(),
                new MaterialRiskDto.PrimarySupplier(
                        "SUP-COD-01", "Kolwezi Cobalt Refinery", "ACTIVE", "CONDITIONAL", "NO"),
                new MaterialRiskDto.LinkedContract(
                        11L, "CTR-010", "CTR-010", "코발트 장기 공급계약", "ACTIVE", null, null),
                null, true, true, List.of(), List.of(), available, blockedReason, OffsetDateTime.now());
    }

    private static ContractRagDto.SourceNews sourceNews(Analysis analysis) {
        return new ContractRagDto.SourceNews(
                analysis.getAnalysisId(), 252L, "코발트 공급사의 납기 지연", "코발트 공급사의 납기 지연",
                "납기 지연 요약", "CD", "COBALT", "PRODUCTION", "warning", 60,
                "https://example.test/a", Instant.now(), Instant.now());
    }

    private static ErpDto.ContextResponse erpContext() {
        return new ErpDto.ContextResponse(
                1L, "MAT-CO-SULF", "황산코발트", "KG",
                new BigDecimal("1300"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1300"), new BigDecimal("200"), new BigDecimal("3600"),
                new BigDecimal("2300"), new BigDecimal("6.5"), new BigDecimal("18.0"),
                null, 9, new BigDecimal("2.5"), new BigDecimal("500"), new BigDecimal("0.84"),
                "OPEN", "CONDITIONAL", "ACTIVE", "NO",
                2L, "SUP-COD-01", 11L, "CTR-010", "VALID", OffsetDateTime.now(), "ERP", false);
    }

    /** ERP·계약 노드까지 실제로 돈 응답. erp_assessment에 노출도 점수가 들어 있는 것이 그 표식이다. */
    private static MultiAgentDto.Response compositeResponse() {
        return new MultiAgentDto.Response(
                UUID.randomUUID(), "news-integration-001", "PRODUCTION", "critical", 70,
                List.of("외부 공급망 신호 점수: 60", "ERP 내부 노출도 점수: 75"),
                Map.of("erp_exposure_score", 75, "exposure_level", "critical",
                        "inventory_days", 6.5, "safety_stock_days", 18.0,
                        "next_inbound_eta_days", 9, "expected_supply_gap_days", 2.5,
                        "stockout_before_eta", true),
                Map.of(),
                Map.of("contract_gap_score", 30, "protection_status", "partial"),
                List.of(Map.of("contract_id", 11, "page", 1,
                        "evidence_text", "납기 지연 발생 시 공급사의 서면 통지 의무와 위약금 조항")),
                List.of("기존 발주의 납기와 수량을 우선 확인합니다."),
                "코발트 공급사의 납기 지연으로 공급 일정에 차질이 예상됩니다.",
                true, null, true, List.of());
    }

    /** KG 게이트에서 조기 종료된 응답. erp_assessment가 비어 있고 점수가 0이다. */
    private static MultiAgentDto.Response earlyExitResponse() {
        return new MultiAgentDto.Response(
                UUID.randomUUID(), "news-integration-001", "PRODUCTION", "normal", 0,
                List.of("공급망 경로 매칭이 없어 조기 종료했습니다."),
                Map.of(), Map.of(), Map.of(), List.of(), List.of(),
                null, false, null, false, List.of());
    }
}

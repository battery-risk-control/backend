package com.example.batteryrisk;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MaterialRiskDto;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.MaterialRiskRepository;
import com.example.batteryrisk.service.ErpExposureContextFactory;
import com.example.batteryrisk.service.ErpService;
import com.example.batteryrisk.service.MaterialRiskService;
import com.example.batteryrisk.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 원자재 위험 화면의 <b>조용히 거짓말하기 쉬운 지점들</b>을 고정하는 회귀 테스트.
 *
 * <p>여기서 막는 것은 전부 "화면은 멀쩡해 보이는데 값이 틀린" 종류다.
 * <ul>
 *   <li><b>Agent 입력이 멀티에이전트와 갈리는 것</b> — 갈리면 같은 자재가 화면마다 다른 점수를
 *       갖는다. 특히 대체 공급사를 빈 목록으로 보내던 옛 우회로 되돌아가지 않는지 본다.</li>
 *   <li><b>데이터 품질 KPI가 실제보다 좋아 보이는 것</b> — 평가하지 못한 자재를 빼고 집계하면
 *       "품질 VALID · 평가 불가 1종"이 함께 뜬다.</li>
 *   <li><b>오래된 재고를 최신인 척 보여주는 것</b> — Agent는 스냅샷 시각을 받지 않아 신선도를
 *       못 보므로 Spring이 판정하지 않으면 몇 달 지난 시드가 계속 VALID로 나온다.</li>
 *   <li><b>무엇을 검색했는지 감추는 것</b> — 기본 질의로 RAG를 돌리고 questions를 비워 보내면
 *       화면이 결과를 해석할 수 없다.</li>
 * </ul>
 */
class MaterialRiskServiceTest {
    private static final String EXPOSURE_URL = "http://localhost:8000/api/v1/internal/erp/exposure";

    /** Agent가 점수를 낸 정상 응답. dataQualityStatus는 VALID다 — 노후 판정은 Spring 몫이다. */
    private static final String SCORED_RESPONSE = """
            {"requestId":"R","affectedMaterialIds":["MAT-CO-SULF"],"affectedSupplierIds":[],
             "affectedContractIds":[],"facts":{},"riskComponents":{"gapRiskScore":0.0,
             "safetyStockRiskScore":100.0,"dependencyRiskScore":100.0,
             "purchaseOrderDelayRiskScore":100.0,"alternativeSupplierRiskScore":0.0},
             "erpExposureScore":55.0,"supplierAssessments":[],"exposureLevel":"WARNING",
             "forcedCritical":false,"contractReviewRequired":true,
             "questionsForContractAgent":[],"calculationEvidence":[],
             "dataQualityStatus":"VALID","manualReviewRequired":false,"warnings":[],
             "ruleVersion":"erp-exposure-v0.1"}
            """;

    /** 필수 값이 빠져 Agent가 점수를 만들지 않은 응답. */
    private static final String UNSCORED_RESPONSE = """
            {"requestId":"R","affectedMaterialIds":["MAT-MN-SULF"],"affectedSupplierIds":[],
             "affectedContractIds":[],"facts":{},"riskComponents":{},
             "erpExposureScore":null,"supplierAssessments":[],"exposureLevel":"UNKNOWN",
             "forcedCritical":false,"contractReviewRequired":false,
             "questionsForContractAgent":[],"calculationEvidence":[],
             "dataQualityStatus":"INCOMPLETE","manualReviewRequired":true,
             "warnings":["ERP 필수 데이터가 누락되었습니다: averageDailyUsage"],
             "ruleVersion":"erp-exposure-v0.1"}
            """;

    private MockRestServiceServer server;
    private MaterialRiskRepository repository;
    private ErpRepository erpRepository;
    private ErpService erpService;
    private RagService ragService;
    private AnalysisRepository analysisRepository;
    private MaterialRiskService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();

        repository = mock(MaterialRiskRepository.class);
        erpRepository = mock(ErpRepository.class);
        erpService = mock(ErpService.class);
        ragService = mock(RagService.class);
        analysisRepository = mock(AnalysisRepository.class);

        when(repository.findSupplierName(anyLong())).thenReturn("Katanga Cobalt Mining");
        when(repository.findContract(anyLong())).thenReturn(Optional.of(new MaterialRiskDto.LinkedContract(
                11L, "CTR-010", "BA-2025-0010", "Cobalt Sulfate Supply Agreement 1",
                "ACTIVE", LocalDate.parse("2025-07-07"), LocalDate.parse("2027-07-02"))));
        when(analysisRepository.findScoredByMaterialCategory(anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(erpRepository.findOpenPurchaseOrders(anyLong(), any(LocalDate.class))).thenReturn(List.of());
        when(erpRepository.findEligibleAlternativeSuppliers(
                anyLong(), anyLong(), any(LocalDate.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        service = new MaterialRiskService(
                repository,
                erpService,
                new ErpExposureContextFactory(erpRepository),
                ragService,
                analysisRepository,
                builder.build(),
                mock(com.example.batteryrisk.service.RiskEventService.class));
    }

    /**
     * <b>P0 회귀 방지.</b> 대체 공급사를 빈 목록으로 보내던 옛 우회로 돌아가면 여기서 깨진다.
     *
     * <p>{@code availableCapacityQuantity}가 실려야 Agent가 승인된 대체 공급사를 APPROVED로
     * 인정한다. 안 실으면 전부 CONDITIONAL로 강등돼 멀티에이전트 브리핑과 점수가 갈린다.
     */
    @Test
    void sendsAlternativeSuppliersWithCapacityAndRequiredQuantity() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        when(erpRepository.findEligibleAlternativeSuppliers(
                anyLong(), anyLong(), any(LocalDate.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new ErpRepository.AlternativeSupplierRow(
                        "SUP-CAN-01", "Sudbury Metals", "APPROVED", "NO",
                        true, true, 21, new BigDecimal("1200"))));

        server.expect(once(), requestTo(EXPOSURE_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.alternativeSuppliers[0].supplierId").value("SUP-CAN-01"))
                .andExpect(jsonPath("$.alternativeSuppliers[0].availableCapacityQuantity").value(1200))
                .andExpect(jsonPath("$.alternativeSuppliers[0].qualificationStatus").value("APPROVED"))
                // 안전재고 부족분. 없으면 Agent가 capacity 충족도를 "필요량 미상"으로 낮춰 잡는다.
                .andExpect(jsonPath("$.requiredQuantity").value(11500))
                // 신선도는 Agent에 맡기지 않는다 — Spring이 실제 스냅샷 시각으로 판정한다.
                .andExpect(jsonPath("$.materialContext.inventorySnapshotAt").doesNotExist())
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        service.overview(true);
        server.verify();
    }

    /**
     * 납기가 지난 발주는 {@code eligibleForEta=false}로 보낸다 — 멀티에이전트와 같은 규칙이다.
     *
     * <p>더 나은 규칙이라서가 아니라 <b>같아야 해서</b> 이렇게 둔다. 병합 후 함께 재검토할 때
     * 이 테스트도 같이 바뀌어야 한다({@code ErpExposureContextFactory} javadoc 2번).
     */
    @Test
    void marksOverduePurchaseOrdersIneligibleForEta() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        when(erpRepository.findOpenPurchaseOrders(anyLong(), any(LocalDate.class))).thenReturn(List.of(
                new ErpRepository.PurchaseOrderRow("POI-1", "PO-1", "MAT-CO-SULF", "SUP-COD-01",
                        "CTR-010", new BigDecimal("500"), "CONFIRMED", LocalDate.now().minusDays(3)),
                new ErpRepository.PurchaseOrderRow("POI-2", "PO-2", "MAT-CO-SULF", "SUP-COD-01",
                        "CTR-010", new BigDecimal("500"), "CONFIRMED", LocalDate.now().plusDays(5))));

        server.expect(once(), requestTo(EXPOSURE_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.purchaseOrders[0].eligibleForEta").value(false))
                .andExpect(jsonPath("$.purchaseOrders[1].eligibleForEta").value(true))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        service.overview(true);
        server.verify();
    }

    /**
     * 재고 스냅샷이 24시간을 넘으면 Agent가 VALID라고 해도 STALE로 내린다.
     *
     * <p>Agent는 스냅샷 시각을 받지 않아 신선도를 볼 수 없다. Spring이 판정하지 않으면 몇 달 지난
     * 고정 시드가 화면에서 계속 "데이터 품질 VALID"로 보인다.
     */
    @Test
    void downgradesDataQualityWhenInventorySnapshotIsOld() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        when(repository.findInventorySnapshotAt(anyLong(), any(OffsetDateTime.class)))
                .thenReturn(Instant.now().minus(11, ChronoUnit.DAYS));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        MaterialRiskDto.Overview overview = service.overview(true);

        assertThat(overview.materials()).singleElement()
                .extracting(MaterialRiskDto.MaterialItem::dataQualityStatus).isEqualTo("STALE");
        assertThat(overview.summary().dataQualityStatus()).isEqualTo("STALE");
        // 노후여도 점수는 그대로 낸다 — "오래됐다"와 "계산할 수 없다"는 다르다.
        assertThat(overview.materials().get(0).score()).isEqualByComparingTo("55.0");
    }

    /** 스냅샷 시각을 모르면 노후로 단정하지 않는다 — 모르는 것을 나쁘다고 말하지 않는다. */
    @Test
    void keepsAgentDataQualityWhenSnapshotTimeIsUnknown() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        when(repository.findInventorySnapshotAt(anyLong(), any(OffsetDateTime.class))).thenReturn(null);
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        assertThat(service.overview(true).summary().dataQualityStatus()).isEqualTo("VALID");
    }

    /**
     * 요약의 데이터 품질은 <b>점수가 나온 자재</b> 중 최악값이다 — 나머지 KPI 3장과 같은 모집단.
     *
     * <p>평가하지 못한 자재를 섞으면 그쪽이 늘 더 나쁜 값이라, 10종 중 1종만 결측이어도 카드가
     * 거기 붙어 나머지 9종의 상태를 가린다. 그 1종은 {@code unavailableCount}로 따로 드러난다.
     */
    @Test
    void summaryDataQualityDescribesAssessedMaterialsOnly() {
        when(repository.findAssessableMaterials()).thenReturn(List.of(
                new MaterialRiskRepository.MaterialRow("MAT-CO-SULF", "Cobalt Sulfate", "COBALT"),
                new MaterialRiskRepository.MaterialRow("MAT-MN-SULF", "Manganese Sulfate", "MANGANESE")));
        when(erpService.buildContext(any(ErpDto.ContextRequest.class)))
                .thenAnswer(invocation -> erpContext(
                        ((ErpDto.ContextRequest) invocation.getArgument(0)).erpMaterialId()));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(UNSCORED_RESPONSE, APPLICATION_JSON));

        MaterialRiskDto.Summary summary = service.overview(true).summary();

        assertThat(summary.assessedMaterialCount()).isEqualTo(1);
        assertThat(summary.unavailableCount()).isEqualTo(1);
        // 평가된 MAT-CO-SULF는 VALID다. 평가하지 못한 MAT-MN-SULF의 INCOMPLETE는 섞이지 않는다.
        assertThat(summary.dataQualityStatus()).isEqualTo("VALID");
    }

    /** 점수를 못 낸 자재도 목록에서 지우지 않고 사유를 달아 맨 뒤로 보낸다. */
    @Test
    void keepsUnevaluatedMaterialInListWithReasonAtTheEnd() {
        when(repository.findAssessableMaterials()).thenReturn(List.of(
                new MaterialRiskRepository.MaterialRow("MAT-MN-SULF", "Manganese Sulfate", "MANGANESE"),
                new MaterialRiskRepository.MaterialRow("MAT-CO-SULF", "Cobalt Sulfate", "COBALT")));
        when(erpService.buildContext(any(ErpDto.ContextRequest.class)))
                .thenAnswer(invocation -> erpContext(
                        ((ErpDto.ContextRequest) invocation.getArgument(0)).erpMaterialId()));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(UNSCORED_RESPONSE, APPLICATION_JSON));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        List<MaterialRiskDto.MaterialItem> materials = service.overview(true).materials();

        assertThat(materials).extracting(MaterialRiskDto.MaterialItem::erpMaterialId)
                .containsExactly("MAT-CO-SULF", "MAT-MN-SULF");
        assertThat(materials.get(1).unavailableReason())
                .isEqualTo("ERP 필수 데이터가 누락되었습니다: averageDailyUsage");
        assertThat(materials.get(1).grade()).isNull();
    }

    /**
     * 계약 질문이 하나도 없어도 questions를 비워 보내지 않는다.
     *
     * <p>비워 보내면 화면이 "무엇을 검색했는지"를 알 수 없어 어떤 조항이 왜 걸렸는지 읽지 못한다.
     */
    @Test
    void contractEvidenceReportsDefaultQueryAsAQuestion() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));
        when(ragService.search(any())).thenReturn(new com.example.batteryrisk.dto.RagDto.SearchResult(
                List.of(), false));

        MaterialRiskDto.ContractEvidence evidence = service.contractEvidence("MAT-CO-SULF");

        assertThat(evidence.questions()).singleElement()
                .extracting(MaterialRiskDto.ContractQuestion::questionCode)
                .isEqualTo("DEFAULT_CONTRACT_REVIEW");
        assertThat(evidence.query()).isEqualTo(evidence.questions().get(0).question());
    }

    /**
     * 검색 결과에 조항 제목이 붙어 나가는지.
     *
     * <p>청크 하나가 {@code 4.01·4.02·4.03}을 통째로 담고 있어서, 제목이 없으면 "납기 지연
     * 위약금이 있는가"를 물어놓고 화면은 어느 조항이 답인지 짚어주지 못한다.
     *
     * <p>제목을 만드는 규칙 자체는 {@code ContractRagServiceTest}가 고정한다(그쪽이 원본이고
     * 이 서비스는 재사용만 한다). 여기서 보는 것은 <b>실제로 태워 보내는지</b> 하나다 —
     * 매핑을 빠뜨리면 화면에 제목이 통째로 빈다.
     */
    @Test
    void contractEvidenceAttachesClauseTitleToEachHit() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));
        when(ragService.search(any())).thenReturn(new com.example.batteryrisk.dto.RagDto.SearchResult(
                List.of(new com.example.batteryrisk.dto.RagDto.SearchItem(
                        "DOC-1", 11L, 12L, 13L, null, null, "CONTRACT", 0, 1,
                        "Article 4\nDELIVERY AND PENALTY\n\n4.01 Delivery Schedule. Seller must deliver...",
                        "hash-1", 0.474, "openai", "v1", false)),
                false));

        MaterialRiskDto.ContractEvidence evidence = service.contractEvidence("MAT-CO-SULF");

        assertThat(evidence.results()).singleElement().satisfies(hit -> {
            assertThat(hit.clauseNo()).isEqualTo("제4조");
            assertThat(hit.clauseTitle()).isEqualTo("제4조 · 납기 및 지연 위약금");
            // 원문은 자르지 않는다 — 제목은 보태는 것이지 본문을 대신하는 게 아니다.
            assertThat(hit.content()).startsWith("Article 4");
            assertThat(hit.pageNumber()).isEqualTo(1);
            assertThat(hit.similarityScore()).isEqualTo(0.474);
        });
    }

    /** 개요는 캐시된다. 화면의 새로고침(refresh=true)만 다시 계산시킨다. */
    @Test
    void cachesOverviewUntilRefreshIsRequested() {
        givenMaterial("MAT-CO-SULF", "Cobalt Sulfate", "COBALT");
        // 호출 2회만 기대한다 — 캐시가 안 먹으면 3회째에서 MockRestServiceServer가 실패한다.
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));
        server.expect(once(), requestTo(EXPOSURE_URL))
                .andRespond(withSuccess(SCORED_RESPONSE, APPLICATION_JSON));

        service.overview(true);
        service.overview(false);
        service.overview(true);

        server.verify();
    }

    private void givenMaterial(String erpMaterialId, String name, String category) {
        when(repository.findAssessableMaterials()).thenReturn(List.of(
                new MaterialRiskRepository.MaterialRow(erpMaterialId, name, category)));
        when(erpService.buildContext(any(ErpDto.ContextRequest.class))).thenReturn(erpContext(erpMaterialId));
    }

    /** 코발트 실측값을 본뜬 ERP Context. 안전재고 부족분 11,500은 requiredQuantity 검증에 쓴다. */
    private static ErpDto.ContextResponse erpContext(String erpMaterialId) {
        return new ErpDto.ContextResponse(
                5L, erpMaterialId, "Cobalt Sulfate", "KG",
                new BigDecimal("7280"), new BigDecimal("429"), new BigDecimal("195"),
                new BigDecimal("156"), new BigDecimal("6500"), new BigDecimal("1000"),
                new BigDecimal("18000"), new BigDecimal("11500"),
                new BigDecimal("6.5"), new BigDecimal("18"),
                LocalDate.now().plusDays(5), 5, BigDecimal.ZERO, new BigDecimal("500"),
                new BigDecimal("0.84"), "CONFIRMED", "APPROVED", "ACTIVE", "NO",
                6L, "SUP-COD-01", 11L, "CTR-010", "VALID",
                OffsetDateTime.now(), "ERP_MOCK", true);
    }
}

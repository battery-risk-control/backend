package com.example.batteryrisk;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.dto.AnalysisDto;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.AnalysisSupplierRecommendationRepository;
import com.example.batteryrisk.service.AnalysisService;
import com.example.batteryrisk.service.ErpExposureRequestService;
import com.example.batteryrisk.service.MultiAgentOrchestrationService;
import com.example.batteryrisk.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 조원 인계문서(B) — material_category가 등급과 무관하게 저장되는지, 하지만 조치(공급사 추천·
 * Chain B 자동 트리거)는 여전히 CRITICAL/WARNING만 타는지 확인한다.
 *
 * <p>이전에는 material_category가 attachSupplierRecommendation(RISKY_SEVERITIES 게이트 안쪽)
 * 에서만 채워져, NORMAL 등급 뉴스는 LLM이 자재를 뽑아줘도 그 값이 통째로 버려졌다
 * (실측: COMPLETED 중 NORMAL 31건 → material_category 전부 NULL).
 */
class AnalysisMaterialCategoryTest {
    private final RestClient fastApiRestClient = mock(RestClient.class);
    private final RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
    private final RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
    private final RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisSupplierRecommendationRepository supplierRecommendationRepository =
            mock(AnalysisSupplierRecommendationRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ErpExposureRequestService erpExposureRequestService =
            mock(ErpExposureRequestService.class);
    private final MultiAgentOrchestrationService multiAgentOrchestrationService =
            mock(MultiAgentOrchestrationService.class);

    private final AnalysisService service = new AnalysisService(
            fastApiRestClient, analysisRepository, supplierRecommendationRepository,
            notificationService, erpExposureRequestService, multiAgentOrchestrationService);

    @BeforeEach
    void setUp() {
        when(analysisRepository.saveAndFlush(any(Analysis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // fastApiRestClient.post().uri(...).body(...).retrieve() 체인 배선. RestClient의
        // .body(Class<T>)는 제네릭이라 Mockito RETURNS_DEEP_STUBS로는 안정적으로 안 잡혀서
        // (실측: 항상 null을 돌려줌) 인터페이스별로 직접 mock을 만들어 이어 붙인다.
        when(fastApiRestClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    private void stubFastApiResponse(AnalysisDto.FastApiAnalyzeResponse response) {
        when(responseSpec.body(AnalysisDto.FastApiAnalyzeResponse.class)).thenReturn(response);
    }

    private static AnalysisDto.FastApiAnalyzeResponse response(String severity, List<String> affectedMaterials) {
        return new AnalysisDto.FastApiAnalyzeResponse(
                true,
                new AnalysisDto.FastApiAnalyzeData(
                        new AnalysisDto.FastApiClassification("PRODUCTION", 0.9, "v1", false),
                        new AnalysisDto.FastApiSeverity(severity, 50.4, List.of("BASE_SCORE_ONLY"), "v1", false),
                        false,
                        affectedMaterials,
                        null,
                        null),
                Instant.now());
    }

    @Test
    void normalSeverityStillAttachesMaterialCategoryButSkipsSupplierRecommendation() {
        stubFastApiResponse(response("NORMAL", List.of("LITHIUM")));

        service.create(new AnalysisDto.AnalyzeRequest(
                null, null, "칠레 리튬 시장 동향", "본문", "TEST", "CL"));

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisRepository, atLeastOnce()).saveAndFlush(captor.capture());
        Analysis saved = captor.getValue();

        assertThat(saved.getMaterialCategory()).isEqualTo("LITHIUM");
        verifyNoInteractions(erpExposureRequestService);
        verify(multiAgentOrchestrationService, never()).generate(any());
    }

    @Test
    void warningSeverityAttachesMaterialCategoryAndTriggersSupplierRecommendation() {
        stubFastApiResponse(response("WARNING", List.of("COBALT")));
        when(erpExposureRequestService.analyzeExposure(any()))
                .thenThrow(new RuntimeException("ERP Exposure 호출 실패(부가기능, 테스트에서는 흡수만 확인)"));

        service.create(new AnalysisDto.AnalyzeRequest(
                null, null, "콩고 코발트 채굴 차질", "본문", "TEST", "CD"));

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisRepository, atLeastOnce()).saveAndFlush(captor.capture());
        Analysis saved = captor.getValue();

        assertThat(saved.getMaterialCategory()).isEqualTo("COBALT");
        verify(erpExposureRequestService).analyzeExposure(any());
    }

    @Test
    void noAffectedMaterialsLeavesMaterialCategoryNull() {
        stubFastApiResponse(response("NORMAL", List.of()));

        service.create(new AnalysisDto.AnalyzeRequest(
                null, null, "무관 기사", "본문", "TEST", "US"));

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisRepository, atLeastOnce()).saveAndFlush(captor.capture());
        Analysis saved = captor.getValue();

        assertThat(saved.getMaterialCategory()).isNull();
        verifyNoInteractions(erpExposureRequestService);
    }
}

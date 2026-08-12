package com.example.batteryrisk;

import com.example.batteryrisk.dto.ContractRagDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.ContractRagRepository;
import com.example.batteryrisk.service.ContractRagService;
import com.example.batteryrisk.service.DocumentService;
import com.example.batteryrisk.service.OutboundDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 계약 · RAG 화면 서비스의 <b>화면 문구를 만들어내는 판정</b>들을 고정한다.
 *
 * <p>여기서 지키려는 것은 두 가지다.
 * <ul>
 *   <li><b>조항 제목</b> — ChromaDB 청크에는 제목 필드가 없어 본문 머리에서 뽑아낸다.
 *       ERP 연결 시드가 영문 계약서라 "Article 4 / DELIVERY AND PENALTY"가 들어오는데,
 *       화면에는 "제4조 · 납기 및 지연 위약금"으로 보여야 한다. 파싱이 깨지면 카드 제목이
 *       통째로 사라지거나 본문 첫 줄이 그대로 노출된다.</li>
 *   <li><b>브리핑 실행 가능 판정</b> — 계약에 ERP 자재·공급사나 자재 대분류가 없으면 멀티에이전트를
 *       돌려도 의미가 없다. 화면이 버튼을 미리 비활성화할 수 있게 같은 판정을 상세 응답에도 싣는다.</li>
 * </ul>
 */
class ContractRagServiceTest {
    private ContractRagRepository repository;
    private ContractRagService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContractRagRepository.class);
        service = new ContractRagService(
                repository,
                mock(DocumentService.class),
                mock(OutboundDocumentService.class),
                mock(RestClient.class));
    }

    // ------------------------------------------------------------ 조항 제목 파싱

    @Test
    void 영문_조항은_한글_라벨을_입혀_보여준다() {
        ContractRagService.ClauseHeading heading = ContractRagService.ClauseHeading.parse(
                "Article 4\nDELIVERY AND PENALTY\n\n4.01 Delivery Schedule. Seller must deliver...");

        assertThat(heading.clauseNo()).isEqualTo("제4조");
        assertThat(heading.rawHeading()).isEqualTo("DELIVERY AND PENALTY");
        assertThat(heading.displayTitle()).isEqualTo("제4조 · 납기 및 지연 위약금");
    }

    @Test
    void 매핑에_없는_영문_표제는_원문을_그대로_쓴다() {
        ContractRagService.ClauseHeading heading = ContractRagService.ClauseHeading.parse(
                "Article 9\nSPECIAL TOOLING\n\n9.01 ...");

        assertThat(heading.displayTitle()).isEqualTo("제9조 · SPECIAL TOOLING");
    }

    @Test
    void 한글_계약서_조항도_그대로_읽는다() {
        ContractRagService.ClauseHeading heading = ContractRagService.ClauseHeading.parse(
                "제3조 (납품 및 지체상금)\n1. \"을\"은 납기일 이내에 납품하여야 한다.");

        assertThat(heading.clauseNo()).isEqualTo("제3조");
        assertThat(heading.displayTitle()).isEqualTo("제3조 · 납품 및 지체상금");
    }

    @Test
    void 조항이_아닌_청크는_첫_줄을_잘라_제목으로_쓴다() {
        ContractRagService.ClauseHeading heading = ContractRagService.ClauseHeading.parse(
                "COBALT SULFATE SUPPLY AGREEMENT\n\nThis agreement is entered into...");

        assertThat(heading.clauseNo()).isNull();
        assertThat(heading.displayTitle()).isEqualTo("COBALT SULFATE SUPPLY AGREEMENT");
    }

    @Test
    void 첫_줄이_길면_잘라서_말줄임한다() {
        String longLine = "이 계약은 당사자 간의 권리와 의무를 규정하며 별도로 정하지 아니한 사항은 상관례에 따른다";
        ContractRagService.ClauseHeading heading = ContractRagService.ClauseHeading.parse(longLine);

        assertThat(heading.displayTitle()).endsWith("…");
        assertThat(heading.displayTitle().length()).isLessThanOrEqualTo(42);
    }

    @Test
    void 빈_청크도_제목을_비우지_않는다() {
        assertThat(ContractRagService.ClauseHeading.parse("").displayTitle()).isEqualTo("(내용 없음)");
        assertThat(ContractRagService.ClauseHeading.parse(null).displayTitle()).isEqualTo("(내용 없음)");
    }

    // ------------------------------------------------------------ 브리핑 실행 가능 판정

    @Test
    void ERP_자재와_관련_뉴스가_모두_있으면_브리핑을_돌릴_수_있다() {
        when(repository.findContract(11L)).thenReturn(Optional.of(contract("MAT-CO-SULF", "SUP-COD-01", "COBALT")));
        when(repository.findDocuments(11L)).thenReturn(List.of(document("OPENAI_API")));
        when(repository.findLatestRelatedNews("COBALT", "CD")).thenReturn(Optional.of(news()));

        ContractRagDto.ContractDetail detail = service.contract(11L);

        assertThat(detail.briefingAvailable()).isTrue();
        assertThat(detail.briefingBlockedReason()).isNull();
        assertThat(detail.embeddingType()).isEqualTo("OPENAI_API");
        assertThat(detail.mockEmbedding()).isFalse();
    }

    /**
     * 계약은 멀쩡한데 그 자재로 분석된 뉴스가 아직 없는 경우. 상세가 "가능"이라 해놓고 눌렀을 때
     * 422가 나면 화면이 거짓말을 한 셈이라, 상세 판정도 뉴스 존재까지 확인해야 한다.
     */
    @Test
    void 관련_뉴스가_없으면_상세에서부터_버튼을_막는다() {
        when(repository.findContract(11L)).thenReturn(Optional.of(contract("MAT-CO-SULF", "SUP-COD-01", "COBALT")));
        when(repository.findDocuments(11L)).thenReturn(List.of(document("OPENAI_API")));
        when(repository.findLatestRelatedNews("COBALT", "CD")).thenReturn(Optional.empty());

        ContractRagDto.ContractDetail detail = service.contract(11L);

        assertThat(detail.briefingAvailable()).isFalse();
        assertThat(detail.briefingBlockedReason()).contains("분석이 끝난 뉴스가 아직 없습니다");
    }

    @Test
    void 자재_대분류가_없으면_브리핑_버튼을_막고_사유를_알려준다() {
        when(repository.findContract(11L)).thenReturn(Optional.of(contract("MAT-CO-SULF", "SUP-COD-01", null)));
        when(repository.findDocuments(11L)).thenReturn(List.of());

        ContractRagDto.ContractDetail detail = service.contract(11L);

        assertThat(detail.briefingAvailable()).isFalse();
        assertThat(detail.briefingBlockedReason()).contains("대분류");
    }

    @Test
    void ERP_공급사가_없으면_브리핑을_막는다() {
        when(repository.findContract(11L)).thenReturn(Optional.of(contract("MAT-CO-SULF", null, "COBALT")));
        when(repository.findDocuments(11L)).thenReturn(List.of());

        assertThat(service.contract(11L).briefingAvailable()).isFalse();
    }

    @Test
    void mock_임베딩은_화면이_알아볼_수_있게_표시한다() {
        when(repository.findContract(11L)).thenReturn(Optional.of(contract("MAT-CO-SULF", "SUP-COD-01", "COBALT")));
        when(repository.findDocuments(11L)).thenReturn(List.of(document("MOCK_TOKEN_HASH")));

        assertThat(service.contract(11L).mockEmbedding()).isTrue();
    }

    @Test
    void 없는_계약은_404로_끝낸다() {
        when(repository.findContract(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.contract(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ERP_CONTRACT_NOT_FOUND);
    }

    @Test
    void 검색어가_비면_FastAPI를_부르기_전에_막는다() {
        assertThatThrownBy(() -> service.search(
                new ContractRagDto.SearchRequest("   ", null, null, null, null, null, null, 5)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private static ContractRagDto.ContractSummary contract(
            String erpMaterialId, String erpSupplierId, String materialCategory) {
        return new ContractRagDto.ContractSummary(
                11L, "CTR-010", "Cobalt Sulfate Supply Agreement 1", "ACTIVE",
                LocalDate.of(2025, 7, 7), LocalDate.of(2027, 7, 2), "USD",
                3L, erpSupplierId, "Katanga Cobalt Mining", "CD",
                5L, erpMaterialId, "황산코발트", materialCategory, 1, 20,
                "INBOUND", null, null, null, null, null, null);
    }

    private static ContractRagDto.SourceNews news() {
        return new ContractRagDto.SourceNews(
                UUID.randomUUID(), 252L, "cobalt mine halt in DRC", "콩고 코발트 광산 가동 중단",
                "콩고 코발트 광산이 가동을 멈췄다.", "CD", "COBALT", "PRODUCTION",
                "CRITICAL", 85, "https://example.com/news", null, null);
    }

    private static ContractRagDto.DocumentItem document(String embeddingType) {
        return new ContractRagDto.DocumentItem(
                "con_abc", "CTR-010_EX-10_CobaltSulfate.txt", "CONTRACT", "text/plain", 1024,
                "COMPLETED", 20, embeddingType, "openai-text-embedding-3-large",
                null, null, null, null);
    }
}

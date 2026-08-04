package com.example.batteryrisk;

import com.example.batteryrisk.dto.AiBriefingDto;
import com.example.batteryrisk.service.AiBriefingReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 브리핑 PDF가 지켜야 할 것을 고정한다.
 *
 * <ul>
 *   <li><b>저장된 값만으로 그려진다</b> — 이 서비스는 {@link AiBriefingDto.BriefingDetail} 하나만
 *       받는다. 협력 객체가 없다는 사실 자체가 "다운로드가 LLM을 다시 부르지 않는다"는 보장이라,
 *       생성자에 무엇이든 끼어드는 순간 이 테스트가 컴파일되지 않는다.</li>
 *   <li><b>값이 비어도 문서가 나온다</b> — 조기 종료된 브리핑은 근거 칸 대부분이 null이다.
 *       그때 예외가 나면 "화면에서는 열리는데 내려받기만 실패하는" 상태가 된다.</li>
 *   <li><b>파일명은 브리핑 생성 시각에서 온다</b> — 내려받은 시각으로 지으면 같은 브리핑을 두 번
 *       받았을 때 다른 파일명이 나와 사용자가 중복을 알아보지 못한다.</li>
 * </ul>
 */
class AiBriefingReportServiceTest {
    private AiBriefingReportService service;

    @BeforeEach
    void setUp() {
        service = new AiBriefingReportService();
    }

    @Test
    void 저장된_브리핑을_PDF로_그린다() {
        byte[] pdf = service.renderPdf(fullDetail());

        // %PDF- 매직 넘버. 뷰어가 파일을 PDF로 인식하는 최소 조건이다.
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    /** 조기 종료·근거 없음처럼 null이 많은 브리핑도 문서로 나와야 한다. */
    @Test
    void 근거가_비어도_문서가_나온다() {
        AiBriefingDto.BriefingDetail sparse = new AiBriefingDto.BriefingDetail(
                UUID.randomUUID(), null, "NEWS", "252", null, "N-1", null, null,
                null, null, null, null, null, null, null,
                false,                       // composite=false — 조기 종료
                "NORMAL", BigDecimal.ZERO,
                null,                        // 본문 없음
                List.of(), List.of(), null, List.of(), null, null, null, null);

        assertThatCode(() -> service.renderPdf(sparse)).doesNotThrowAnyException();
        assertThat(service.renderPdf(sparse).length).isGreaterThan(500);
    }

    /**
     * 파일명 시각은 <b>저장된 오프셋에 흔들리지 않는다</b>.
     *
     * <p>JDBC는 DB에 {@code 14:14+09}로 들어 있는 값을 {@code 05:14Z}로 돌려주는데, 화면은
     * 브라우저 시간대로 되돌려 14:14로 보여준다. 그대로 찍으면 같은 브리핑을 화면은 오후 2시,
     * PDF는 오전 5시에 만들었다고 말한다(실측 2026-08-03).
     *
     * <p>그래서 같은 순간을 다른 오프셋으로 표현한 두 값이 같은 파일명을 내야 한다. 이 테스트는
     * 특정 시간대를 못 박지 않으므로 어느 장비에서 돌려도 결과가 같다.
     */
    @Test
    void 파일명은_저장된_오프셋에_흔들리지_않는다() {
        OffsetDateTime kst = OffsetDateTime.of(2026, 8, 3, 14, 25, 30, 0, ZoneOffset.ofHours(9));
        OffsetDateTime sameInstantInUtc = kst.withOffsetSameInstant(ZoneOffset.UTC);

        assertThat(service.pdfFileName(detailCreatedAt(sameInstantInUtc)))
                .isEqualTo(service.pdfFileName(detailCreatedAt(kst)));
    }

    /** 시간대 변환이 실제로 걸려 있는지 — 서버 시간대로 옮긴 벽시계 시각이 파일명에 들어간다. */
    @Test
    void 파일명은_서버_시간대의_벽시계_시각을_쓴다() {
        OffsetDateTime created = OffsetDateTime.of(2026, 8, 3, 14, 25, 30, 0, ZoneOffset.ofHours(9));
        String expected = "ai-briefing-"
                + created.atZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".pdf";

        assertThat(service.pdfFileName(detailCreatedAt(created))).isEqualTo(expected);
    }

    /** 생성 시각이 없는 옛 행이라도 파일명은 나와야 한다 — 내려받기가 막히면 안 된다. */
    @Test
    void 생성_시각이_없어도_파일명을_만든다() {
        AiBriefingDto.BriefingDetail noDate = new AiBriefingDto.BriefingDetail(
                UUID.randomUUID(), null, "NEWS", "252", "제목", "N-1", null, null,
                null, null, null, null, null, null, null,
                true, "WARNING", BigDecimal.TEN, "본문",
                List.of(), List.of(), null, List.of(), null, null, null, null);

        assertThat(service.pdfFileName(noDate)).startsWith("ai-briefing-").endsWith(".pdf");
    }

    private static AiBriefingDto.BriefingDetail detailCreatedAt(OffsetDateTime createdAt) {
        return new AiBriefingDto.BriefingDetail(
                UUID.randomUUID(), null, "NEWS", "252", "제목", "N-1", null, null,
                null, null, null, null, null, null, null,
                true, "WARNING", BigDecimal.TEN, "본문",
                List.of(), List.of(), null, List.of(), null, null, "MANUAL", createdAt);
    }

    private static AiBriefingDto.BriefingDetail fullDetail() {
        AiBriefingDto.Step external =
                new AiBriefingDto.Step("외부 이벤트", "WARNING", BigDecimal.valueOf(60), null);
        AiBriefingDto.Step erp =
                new AiBriefingDto.Step("ERP 노출", "CRITICAL", BigDecimal.valueOf(75), null);
        AiBriefingDto.Step rag =
                new AiBriefingDto.Step("계약 RAG", null, null, "3개 조항");
        AiBriefingDto.Step finalRisk =
                new AiBriefingDto.Step("최종 위험", "CRITICAL", BigDecimal.valueOf(70), null);

        return new AiBriefingDto.BriefingDetail(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "NEWS", "252",
                "DRC-르완다 긴장 고조, 코발트 공급망 위험",
                "N-252",
                UUID.randomUUID(),
                "DRC-Rwanda tensions escalate over mineral-rich border regions",
                "MAT-CO-SULF", "SUP-CO-001", null, 11L,
                "황산코발트", "COBALT", "PRODUCTION",
                true,
                "CRITICAL", BigDecimal.valueOf(70),
                "콩고민주공화국 국경 지역 긴장이 고조되어 코발트 조달에 차질이 예상됩니다.",
                List.of("주 공급사 의존도 84%", "다음 입고 전 재고 소진 우려"),
                List.of("대체 공급사 3곳 견적 요청", "안전재고 상향 검토"),
                new AiBriefingDto.ErpEvidence(
                        BigDecimal.valueOf(75), "CRITICAL",
                        BigDecimal.valueOf(6.5), BigDecimal.valueOf(18),
                        9, BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.84), true),
                List.of(Map.of("contract_id", 11, "page", 1,
                        "evidence_text", "불가항력 조항은 30일 이내 서면 통지를 요구한다.")),
                new AiBriefingDto.EvidenceChain(external, erp, rag, finalRisk),
                new AiBriefingDto.VerificationMeta(
                        true, true, null, 1, List.of("계약 근거가 1건뿐입니다."),
                        11L, 1, "procurement-risk-v1", false),
                "MANUAL",
                OffsetDateTime.of(2026, 8, 3, 14, 25, 30, 0, ZoneOffset.ofHours(9)));
    }
}

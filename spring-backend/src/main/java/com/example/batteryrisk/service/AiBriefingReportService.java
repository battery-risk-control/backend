package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.AiBriefingDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * AI 브리핑 1건을 PDF 문서로 낸다.
 *
 * <p><b>저장된 값만 쓴다.</b> 다운로드는 {@code ai_briefings}에 이미 들어 있는 그 행을 그리는
 * 일이라 LLM도 멀티에이전트도 다시 부르지 않는다. 다시 부르면 같은 브리핑을 두 번 받을 때
 * 내용이 달라지고, 화면에서 읽고 결재에 올린 문서가 화면과 다른 말을 하게 된다.
 *
 * <p>그래서 재료는 {@link AiBriefingService#get}이 화면에 주는 것과 <b>완전히 같은</b>
 * {@link AiBriefingDto.BriefingDetail} 하나다. 문서 전용 조회를 따로 두지 않는 게 핵심이다 —
 * 두 벌이 되는 순간 어느 쪽이 맞는지 대조할 방법이 사라진다.
 *
 * <p>구성 순서도 화면(좌측 본문 → 우측 분석 근거)을 그대로 따라간다. 값이 같아도 순서가 다르면
 * 사람이 두 화면을 대조할 때 같은 것을 못 찾는다.
 *
 * <p>화면과 다른 곳은 <b>계약 근거 한 군데</b>다. 화면은 자리가 좁아 첫 건만 보여주는데, 문서는
 * 전부 싣는다 — 근거를 감춘 감사 문서는 쓸모가 없다. 값을 바꾸는 게 아니라 화면이 자른 것을
 * 펴는 것뿐이라 "화면과 다른 말"이 되지는 않는다.
 *
 * <p>한글은 {@link ErpImportReportService}와 같은 NanumGothic(OFL)을 PDF에 임베드한다. 폰트를
 * 불러오는 코드가 그쪽과 겹치는데, 지금 그 파일이 다른 작업으로 열려 있어 공통화하지 않고 이쪽에
 * 따로 뒀다. 두 리포트가 안정되면 한곳으로 모으는 게 맞다.
 */
@Service
public class AiBriefingReportService {

    private static final String FONT_REGULAR = "/fonts/NanumGothic-Regular.ttf";
    private static final String FONT_BOLD = "/fonts/NanumGothic-Bold.ttf";

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 시각을 찍을 시간대. 컨테이너가 {@code TZ=Asia/Seoul}로 뜨므로 실제로는 KST다.
     *
     * <p><b>이 변환이 없으면 화면과 문서가 다른 시각을 말한다.</b> JDBC가 돌려주는
     * {@code OffsetDateTime}은 UTC 오프셋을 달고 오는데(실측: DB에 {@code 14:14+09}로 저장된 행이
     * {@code 05:14Z}로 온다), 화면은 브라우저 시간대로 되돌려 14:14로 보여준다. 그대로 찍으면
     * 같은 브리핑을 화면은 오후 2시, PDF는 오전 5시에 만들었다고 말하게 된다.
     *
     * <p>고정값이 아니라 {@code systemDefault()}인 것은 배포 시간대를 코드 수정 없이 바꿀 수
     * 있게 하기 위해서다.
     */
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();

    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xD1, 0xD5, 0xDB);
    private static final Color HEAD_BG = new Color(0xF3, 0xF4, 0xF6);
    private static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);
    private static final Color WARN = new Color(0xB4, 0x53, 0x09);
    private static final Color OK = new Color(0x04, 0x78, 0x57);

    /** 임베드용 폰트. {@link BaseFont}는 스레드 안전하게 재사용할 수 있어 한 번만 만든다. */
    private volatile BaseFont regular;
    private volatile BaseFont bold;

    /** 브리핑 상세를 PDF 바이트로 그린다. */
    public byte[] renderPdf(AiBriefingDto.BriefingDetail detail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 44, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer(regular(), detail.createdAt()));
            document.open();
            writeCover(document, detail);
            writeVerdict(document, detail);
            writeBriefingText(document, detail);
            writeErpEvidence(document, detail);
            writeContractFindings(document, detail);
            writeRecommendedActions(document, detail);
            writeRiskReasons(document, detail);
            writeEvidenceChain(document, detail);
            writeVerification(document, detail);
            document.close();
        } catch (BusinessException exception) {
            throw exception;   // 폰트 누락처럼 이미 사람이 읽을 수 있게 만든 사유는 그대로 올린다
        } catch (RuntimeException exception) {
            // OpenPDF의 DocumentException은 unchecked라 여기로 들어온다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "브리핑 PDF를 만들지 못했습니다: " + exception.getMessage());
        }
        return out.toByteArray();
    }

    /**
     * {@code ai-briefing-20260803-153000.pdf} 꼴의 파일명.
     *
     * <p>생성 시각은 브리핑을 만든 시각이지 내려받은 시각이 아니다 — 같은 브리핑을 두 번 받으면
     * 같은 파일명이 나와야 사용자가 중복을 알아본다.
     *
     * <p>제목(한글)을 파일명에 넣지 않는다. {@code Content-Disposition} 인코딩 문제를 떠나,
     * 브리핑 제목에는 기사 원문 제목이 그대로 들어가 파일명에 못 쓰는 문자가 섞인다.
     */
    public String pdfFileName(AiBriefingDto.BriefingDetail detail) {
        OffsetDateTime stamp = detail.createdAt() == null ? OffsetDateTime.now() : detail.createdAt();
        return "ai-briefing-" + FILE_STAMP.format(local(stamp)) + ".pdf";
    }

    /** 화면과 같은 시각을 찍기 위해 {@link #DISPLAY_ZONE}으로 옮긴다. */
    private static ZonedDateTime local(OffsetDateTime value) {
        return value.atZoneSameInstant(DISPLAY_ZONE);
    }

    // ------------------------------------------------------------------ 그리기

    private void writeCover(Document document, AiBriefingDto.BriefingDetail detail) {
        Paragraph title = new Paragraph("AI 공급망 리스크 브리핑", font(bold(), 22, INK));
        title.setSpacingAfter(6);
        document.add(title);

        Paragraph subject = new Paragraph(
                text(detail.subjectTitle(), "제목 없음"), font(regular(), 13, MUTED));
        subject.setSpacingAfter(18);
        document.add(subject);

        PdfPTable meta = keyValueTable();
        addKeyValue(meta, "대상 유형", sourceTypeLabel(detail.sourceType()));
        addKeyValue(meta, "생성 일시",
                detail.createdAt() == null ? "-" : DISPLAY.format(local(detail.createdAt())));
        addKeyValue(meta, "생성 경로", triggerLabel(detail.triggerType()));
        addKeyValue(meta, "브리핑 ID",
                detail.briefingId() == null ? "-" : detail.briefingId().toString());
        String erpLink = joinNonBlank(
                detail.erpMaterialId(), detail.erpSupplierId(), detail.erpContractId());
        addKeyValue(meta, "ERP 연결", erpLink.isEmpty() ? "-" : erpLink);
        // 자재·계약에서 넘어온 브리핑은 분석 대상과 외부신호 기사가 다르다. 화면 상단이 밝히는
        // 것과 같은 값을 문서에도 남긴다 — 무엇을 근거로 삼았는지가 결재에서 가장 먼저 나오는
        // 질문이다.
        if (detail.sourceHeadline() != null
                && !detail.sourceHeadline().equals(detail.subjectTitle())) {
            addKeyValue(meta, "외부신호 출처", detail.sourceHeadline());
        }
        meta.setSpacingAfter(16);
        document.add(meta);
    }

    /**
     * 위험 판정 한 줄. 화면과 같은 규칙으로 {@code composite=false}는 등급을 감춘다 — 조기 종료된
     * 실행은 0점·NORMAL로 저장되므로 그대로 찍으면 "정상"이라는 판정이 없는데 있는 것이 된다.
     */
    private void writeVerdict(Document document, AiBriefingDto.BriefingDetail detail) {
        if (!detail.composite()) {
            document.add(noticeBox(
                    "멀티에이전트가 ERP · 계약 노드까지 가지 못해 종합 점수가 나오지 않았습니다.\n"
                            + "아래 내용은 그 사유와 외부신호까지의 결과입니다.",
                    WARN, new Color(0xFF, 0xF7, 0xED)));
            return;
        }
        Color tone = levelColor(detail.procurementRiskLevel());
        Paragraph verdict = new Paragraph(
                text(detail.procurementRiskLevel(), "-") + " · " + round(detail.procurementRiskScore()) + "점",
                font(bold(), 16, tone));
        verdict.setSpacingAfter(14);
        document.add(verdict);
    }

    private void writeBriefingText(Document document, AiBriefingDto.BriefingDetail detail) {
        if (isBlank(detail.briefing())) {
            return;
        }
        document.add(heading("브리핑"));
        Paragraph body = new Paragraph(detail.briefing(), font(regular(), 10.5f, INK));
        body.setLeading(16);
        body.setSpacingAfter(14);
        document.add(body);
    }

    private void writeErpEvidence(Document document, AiBriefingDto.BriefingDetail detail) {
        AiBriefingDto.ErpEvidence erp = detail.erpEvidence();
        // 화면과 같은 조건 — 노출 점수가 없으면 ERP 노드를 타지 않은 것이라 칸 자체를 내지 않는다.
        if (erp == null || erp.exposureScore() == null) {
            return;
        }
        document.add(heading("ERP 노출 근거"));

        PdfPTable table = keyValueTable();
        addKeyValue(table, "ERP 노출도", round(erp.exposureScore())
                + (isBlank(erp.exposureLevel()) ? "" : " · " + erp.exposureLevel()));
        addKeyValue(table, "현재 재고", days(erp.inventoryDays()));
        addKeyValue(table, "안전재고", days(erp.safetyStockDays()));
        addKeyValue(table, "예상 입고",
                erp.nextInboundEtaDays() == null ? "—" : erp.nextInboundEtaDays() + "일 후");
        addKeyValue(table, "공급 공백", days(erp.expectedSupplyGapDays()));
        addKeyValue(table, "공급사 의존도", ratio(erp.supplierDependencyRatio()));
        table.setSpacingAfter(erp.stockoutBeforeEta() ? 4 : 14);
        document.add(table);

        if (erp.stockoutBeforeEta()) {
            document.add(footnote(
                    "다음 입고 전에 재고가 소진될 가능성이 있어 점수와 무관하게 심각으로 격상되었습니다."));
        }
    }

    /**
     * 계약 근거. <b>화면은 첫 건만 보여주지만 문서는 전부 싣는다</b> — 근거를 감춘 감사 문서는
     * 쓸모가 없다. 값을 바꾸는 게 아니라 화면이 자리 때문에 자른 것을 펴는 것이다.
     */
    private void writeContractFindings(Document document, AiBriefingDto.BriefingDetail detail) {
        List<Map<String, Object>> findings = detail.contractFindings();
        if (findings == null || findings.isEmpty()) {
            return;
        }
        document.add(heading("계약서에서 확인된 근거"));

        PdfPTable table = dataTable(new float[]{14, 10, 76}, "계약 ID", "페이지", "조항 본문");
        for (Map<String, Object> finding : findings) {
            addCell(table, textOf(finding.get("contract_id")), Element.ALIGN_CENTER, INK);
            addCell(table, textOf(finding.get("page")), Element.ALIGN_CENTER, INK);
            Object evidence = finding.get("evidence_text");
            addCell(table, evidence == null ? "조항 본문이 없습니다." : String.valueOf(evidence),
                    Element.ALIGN_LEFT, INK);
        }
        table.setSpacingAfter(14);
        document.add(table);
    }

    private void writeRecommendedActions(Document document, AiBriefingDto.BriefingDetail detail) {
        writeBulletSection(document, "구매팀 권고 조치", detail.recommendedActions());
    }

    private void writeRiskReasons(Document document, AiBriefingDto.BriefingDetail detail) {
        writeBulletSection(document, "판단 근거", detail.riskReasons());
    }

    /** 우측 "분석 근거" 4칸. 네 칸이 곧 멀티에이전트 실행 순서이자 가중치 구성이다. */
    private void writeEvidenceChain(Document document, AiBriefingDto.BriefingDetail detail) {
        AiBriefingDto.EvidenceChain chain = detail.evidenceChain();
        if (chain == null) {
            return;
        }
        document.add(heading("분석 근거"));

        PdfPTable table = dataTable(new float[]{30, 70}, "단계", "결과");
        addEvidenceRow(table, "외부 이벤트", chain.externalSignal());
        addEvidenceRow(table, "ERP 노출", chain.erpExposure());
        addEvidenceRow(table, "계약 RAG", chain.contractRag());
        addEvidenceRow(table, "최종 위험", chain.finalRisk());
        table.setSpacingAfter(4);
        document.add(table);
        document.add(footnote("외부 이벤트 → ERP → 계약 RAG → 브리핑"));
    }

    /**
     * 검증 메타데이터. 화면과 같은 값을 같은 이름으로 남긴다 — 여기 적힌 필드명이 곧 DB 컬럼명이라
     * 문제가 생겼을 때 문서만 들고도 원본 행을 찾아갈 수 있다.
     */
    private void writeVerification(Document document, AiBriefingDto.BriefingDetail detail) {
        AiBriefingDto.VerificationMeta meta = detail.verification();
        if (meta == null) {
            return;
        }
        document.add(heading("검증 메타데이터"));

        PdfPTable table = keyValueTable();
        addKeyValue(table, "review_passed", String.valueOf(meta.reviewPassed()));
        addKeyValue(table, "llm_used", String.valueOf(meta.llmUsed()));
        addKeyValue(table, "warnings", String.valueOf(meta.warningCount()));
        addKeyValue(table, "contract_id",
                meta.contractId() == null ? "—" : String.valueOf(meta.contractId()));
        addKeyValue(table, "page",
                meta.contractPage() == null ? "—" : String.valueOf(meta.contractPage()));
        addKeyValue(table, "weight_version",
                isBlank(meta.weightVersion()) ? "rule version 미기록" : meta.weightVersion());
        table.setSpacingAfter(4);
        document.add(table);

        if (!isBlank(meta.llmError())) {
            document.add(footnote("LLM 문구 생성 실패: " + meta.llmError()));
        }
        if (meta.warnings() != null) {
            for (String warning : meta.warnings()) {
                document.add(footnote(warning));
            }
        }
        // mock 데이터로 만든 브리핑이 결재에 올라가는 것을 막는다. 화면에서는 눈에 덜 띄어도
        // 문서로 나가면 출처를 아는 사람이 없다.
        if (meta.mock()) {
            document.add(noticeBox(
                    "외부신호의 출처 분석이 mock 데이터입니다. 실제 수집 기사로 만든 브리핑이 아닙니다.",
                    DANGER, new Color(0xFE, 0xF2, 0xF2)));
        }
    }

    private void writeBulletSection(Document document, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        document.add(heading(title));
        for (String item : items) {
            Paragraph line = new Paragraph("• " + item, font(regular(), 10, INK));
            line.setLeading(15);
            line.setIndentationLeft(6);
            line.setSpacingAfter(2);
            document.add(line);
        }
        Paragraph spacer = new Paragraph(" ", font(regular(), 6, INK));
        spacer.setSpacingAfter(8);
        document.add(spacer);
    }

    /** 화면 {@code EvidenceRow}와 같은 규칙 — 등급이 없는 칸은 note("3개 조항")가 값을 대신한다. */
    private void addEvidenceRow(PdfPTable table, String label, AiBriefingDto.Step step) {
        addCell(table, label, Element.ALIGN_LEFT, MUTED);
        if (step == null) {
            addCell(table, "—", Element.ALIGN_LEFT, INK);
            return;
        }
        String value = isBlank(step.level())
                ? text(step.note(), "—")
                : step.level() + (step.score() == null ? "" : " · " + round(step.score()));
        addCell(table, value, Element.ALIGN_LEFT, isBlank(step.level()) ? INK : levelColor(step.level()));
    }

    // ------------------------------------------------------------------ 조각

    private Paragraph heading(String text) {
        Paragraph heading = new Paragraph(text, font(bold(), 13, INK));
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(8);
        return heading;
    }

    private Paragraph footnote(String text) {
        Paragraph note = new Paragraph(text, font(regular(), 9, MUTED));
        note.setLeading(13);
        note.setSpacingAfter(10);
        return note;
    }

    private PdfPTable noticeBox(String text, Color border, Color background) {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell(new Phrase(text, font(regular(), 10, INK)));
        cell.setPadding(10);
        cell.setBackgroundColor(background);
        cell.setBorderColor(border);
        cell.setBorderWidth(1);
        box.addCell(cell);
        box.setSpacingBefore(4);
        box.setSpacingAfter(16);
        return box;
    }

    private PdfPTable keyValueTable() {
        PdfPTable table = new PdfPTable(new float[]{30, 70});
        table.setWidthPercentage(100);
        return table;
    }

    private void addKeyValue(PdfPTable table, String key, String value) {
        PdfPCell keyCell = new PdfPCell(new Phrase(key, font(bold(), 10, MUTED)));
        keyCell.setPadding(6);
        keyCell.setBackgroundColor(HEAD_BG);
        keyCell.setBorderColor(LINE);
        table.addCell(keyCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font(regular(), 10, INK)));
        valueCell.setPadding(6);
        valueCell.setBorderColor(LINE);
        table.addCell(valueCell);
    }

    /** 페이지를 넘어가도 헤더가 반복되는 표. 셀 안 긴 문자열은 PdfPCell이 알아서 줄바꿈한다. */
    private PdfPTable dataTable(float[] widths, String... headers) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, font(bold(), 9, INK)));
            cell.setPadding(5);
            cell.setBackgroundColor(HEAD_BG);
            cell.setBorderColor(LINE);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        return table;
    }

    private void addCell(PdfPTable table, String text, int alignment, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "-" : text, font(regular(), 9, color)));
        cell.setPadding(5);
        cell.setBorderColor(LINE);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    // ------------------------------------------------------------------ 폰트

    private BaseFont regular() {
        if (regular == null) regular = load(FONT_REGULAR);
        return regular;
    }

    private BaseFont bold() {
        if (bold == null) bold = load(FONT_BOLD);
        return bold;
    }

    private static BaseFont load(String resource) {
        try (InputStream stream = AiBriefingReportService.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "PDF 한글 폰트를 찾을 수 없습니다: " + resource);
            }
            // IDENTITY_H + EMBEDDED라야 한글이 뷰어 폰트에 기대지 않고 그대로 찍힌다.
            // 임베드는 기본이 서브셋이라 실제로 쓴 글자만 들어가고 PDF가 커지지 않는다.
            return BaseFont.createFont(resource, BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    BaseFont.CACHED, stream.readAllBytes(), null);
        } catch (IOException | com.lowagie.text.DocumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "PDF 한글 폰트를 불러오지 못했습니다: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ 포맷

    private static Color levelColor(String level) {
        if ("CRITICAL".equals(level)) return DANGER;
        if ("WARNING".equals(level)) return WARN;
        if ("NORMAL".equals(level)) return OK;
        return INK;
    }

    private static String sourceTypeLabel(String sourceType) {
        return switch (text(sourceType, "")) {
            case "NEWS" -> "뉴스";
            case "MATERIAL" -> "원자재";
            case "CONTRACT" -> "계약";
            default -> text(sourceType, "-");
        };
    }

    /** 화면이 자동 실행과 수동 생성을 구분해 보여주므로 문서도 같은 말을 쓴다. */
    private static String triggerLabel(String triggerType) {
        return switch (text(triggerType, "")) {
            case "AUTO" -> "자동 분석";
            case "MANUAL" -> "수동 생성";
            default -> text(triggerType, "-");
        };
    }

    /** 화면의 {@code Math.round}와 같은 결과를 낸다 — 반올림 방식이 다르면 점수가 1점씩 어긋난다. */
    private static String round(BigDecimal value) {
        return value == null ? "—" : value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** null은 "0"이 아니라 "—"로 — 값이 없는 것과 0인 것은 다르다(화면 formatDays와 같은 방침). */
    private static String days(BigDecimal value) {
        return value == null ? "—" : trim(value) + "일";
    }

    private static String ratio(BigDecimal value) {
        return value == null
                ? "—"
                : value.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
    }

    /** 6.5는 "6.5", 18.0은 "18"로 — 화면이 소수점 없는 값에 ".0"을 붙이지 않는다. */
    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String textOf(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static String text(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String joinNonBlank(String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) continue;
            if (joined.length() > 0) joined.append(" · ");
            joined.append(value);
        }
        return joined.toString();
    }

    /** 모든 쪽 하단에 "n / m 페이지 · 생성 시각". 결재에 올라가는 문서라 쪽이 빠졌는지 보여야 한다. */
    private static final class Footer extends PdfPageEventHelper {
        private final BaseFont font;
        private final String generatedAt;
        private PdfTemplate totalPages;
        private int lastPage;

        private Footer(BaseFont font, OffsetDateTime generatedAt) {
            this.font = font;
            this.generatedAt = generatedAt == null ? "-" : DISPLAY.format(local(generatedAt));
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(40, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            lastPage = writer.getPageNumber();
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase("브리핑 생성 " + generatedAt, new Font(font, 8, Font.NORMAL, MUTED)),
                    document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase(writer.getPageNumber() + " / ", new Font(font, 8, Font.NORMAL, MUTED)),
                    document.right() - 40, y, 0);
            writer.getDirectContent().addTemplate(totalPages, document.right() - 40, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            // 총 쪽수는 마지막 쪽을 그리고 나서야 확정된다. 자리만 잡아두고 여기서 채운다.
            totalPages.beginText();
            totalPages.setFontAndSize(font, 8);
            totalPages.setTextMatrix(0, 0);
            // 앞 조각이 "N / "로 끝나므로 여기서 한 칸 더 띄우지 않으면 "1 /3"처럼 붙어 나온다.
            totalPages.showText(" " + lastPage + " 페이지");
            totalPages.endText();
        }
    }
}

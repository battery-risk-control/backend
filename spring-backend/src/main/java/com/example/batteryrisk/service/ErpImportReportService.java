package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.dto.ErpImportReportModel;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ERP 적재 검증 보고서 생성 — PDF(공식 보고서)와 오류 목록 CSV(작업용).
 *
 * <p><b>검증은 하지 않는다.</b> 숫자는 전부 {@link ErpImportService}가 돌린 결과를 그대로 옮긴다.
 * 여기서 품질점수나 오류 수를 다시 세면 화면과 보고서가 조용히 달라지고, 그때 어느 쪽이 맞는지
 * 아무도 판단할 수 없게 된다. 이 클래스가 하는 일은 <b>조립과 그리기</b>뿐이다.
 *
 * <p>최종 반영 보고서의 "누가 언제 몇 건"은 {@link ErpImportReceiptService}가 서명한 영수증에서만
 * 온다 — 프론트가 보낸 숫자는 어떤 경로로도 PDF에 들어가지 않는다.
 *
 * <p>한글은 NanumGothic(OFL)을 <b>PDF에 임베드</b>해서 낸다. 뷰어에 설치된 폰트에 기대면
 * 서버·PC·모바일마다 다르게 보이고, 폰트가 없는 환경에서는 통째로 네모로 나온다. 폰트 파일은
 * jar 안에 들어가므로 Docker 이미지에 따로 설치할 게 없다.
 */
@Service
public class ErpImportReportService {
    public static final String TYPE_PRE_COMMIT = "PRE_COMMIT";
    public static final String TYPE_POST_COMMIT = "POST_COMMIT";

    /** PDF에 싣는 오류·경고 최대 건수. 넘치면 안내문을 달고 전체는 CSV로 넘긴다. */
    private static final int MAX_ISSUES_IN_PDF = 100;

    private static final String FONT_REGULAR = "/fonts/NanumGothic-Regular.ttf";
    private static final String FONT_BOLD = "/fonts/NanumGothic-Bold.ttf";

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xD1, 0xD5, 0xDB);
    private static final Color HEAD_BG = new Color(0xF3, 0xF4, 0xF6);
    private static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);
    private static final Color WARN = new Color(0xB4, 0x53, 0x09);
    private static final Color OK = new Color(0x04, 0x78, 0x57);

    private final ErpImportService importService;
    private final ErpImportReceiptService receiptService;
    private final String systemName;

    /** 임베드용 폰트. {@link BaseFont}는 스레드 안전하게 재사용할 수 있어 한 번만 만든다. */
    private volatile BaseFont regular;
    private volatile BaseFont bold;

    public ErpImportReportService(
            ErpImportService importService,
            ErpImportReceiptService receiptService,
            @Value("${app.report.system-name:배터리 공급망 리스크 관리 시스템}") String systemName) {
        this.importService = importService;
        this.receiptService = receiptService;
        this.systemName = systemName;
    }

    // ---------------------------------------------------------------- 공개 API

    /**
     * 보고서 모델을 조립한다. 올린 파일을 {@link ErpImportService#previewForReport}로 다시 검증하는데,
     * 검증은 파일 내용만 보고 결정되므로 화면에서 본 결과와 같은 값이 나온다.
     *
     * @param receiptToken POST_COMMIT일 때 필수. 서명이 맞지 않으면 예외로 떨어진다.
     */
    public ErpImportReportModel buildModel(
            List<MultipartFile> files, String reportType, String receiptToken) {
        String type = normalizeType(reportType);
        ErpImportDto.PreviewResponse preview = importService.previewForReport(files);

        List<ErpImportReportModel.FileRow> fileRows = preview.files().stream()
                .map(file -> new ErpImportReportModel.FileRow(
                        file.fileName(),
                        file.targetLabel() == null ? "미판별" : file.targetLabel(),
                        file.sizeBytes(), file.rowCount(),
                        file.errorCount(), file.warningCount(), file.duplicateCount(),
                        file.result()))
                .toList();

        List<ErpImportReportModel.TableEstimate> estimates = preview.summary().stream()
                .map(count -> new ErpImportReportModel.TableEstimate(
                        count.targetTable(), count.label(), count.rowCount()))
                .toList();

        List<ErpImportReportModel.MappingRow> mappings = new ArrayList<>();
        for (ErpImportDto.FileAnalysis file : preview.files()) {
            for (ErpImportDto.ColumnMapping column : file.columns()) {
                mappings.add(new ErpImportReportModel.MappingRow(
                        file.fileName(), column.sourceColumn(),
                        column.targetField() == null ? "-" : column.targetField(),
                        column.description(), column.required(), column.status(), column.sample()));
            }
        }

        List<ErpImportReportModel.IssueRow> allIssues = collectIssues(preview);

        return new ErpImportReportModel(
                type,
                OffsetDateTime.now(),
                systemName,
                receiptService.currentUsername(),
                new ErpImportReportModel.Overview(
                        preview.files().size(), preview.totalRows(), preview.totalErrors(),
                        preview.totalWarnings(), preview.totalDuplicates(),
                        preview.qualityScore(), preview.committable()),
                fileRows,
                estimates,
                List.copyOf(mappings),
                allIssues.stream().limit(MAX_ISSUES_IN_PDF).toList(),
                allIssues.size(),
                TYPE_POST_COMMIT.equals(type) ? commitOf(receiptToken) : null);
    }

    /** 모델을 PDF 바이트로 그린다. */
    public byte[] renderPdf(ErpImportReportModel model) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 44, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer(baseRegular(), model.generatedAt()));
            document.open();
            writeCover(document, model);
            writeOverview(document, model);
            writeFileResults(document, model);
            writeTableEstimates(document, model);
            writeMappings(document, model);
            writeIssues(document, model);
            writeCommit(document, model);
            document.close();
        } catch (BusinessException exception) {
            throw exception;   // 폰트 누락처럼 이미 사람이 읽을 수 있게 만든 사유는 그대로 올린다
        } catch (RuntimeException exception) {
            // OpenPDF의 DocumentException은 unchecked라 여기로 들어온다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "검증 보고서 PDF를 만들지 못했습니다: " + exception.getMessage());
        }
        return out.toByteArray();
    }

    /**
     * 전체 오류 목록 CSV. PDF가 상위 100건만 싣기 때문에 "나머지를 보려면" 쓰는 작업용 파일이다.
     *
     * <p>엑셀에서 열릴 걸 전제로 두 가지를 처리한다. BOM을 붙여 한글이 깨지지 않게 하고,
     * {@code =}·{@code +} 등으로 시작하는 값 앞에 작은따옴표를 넣어 수식으로 실행되지 않게 한다 —
     * 오류 메시지에는 사용자가 올린 원본 값이 그대로 들어가므로 그게 수식일 수 있다.
     */
    public byte[] renderErrorCsv(List<MultipartFile> files) {
        ErpImportDto.PreviewResponse preview = importService.previewForReport(files);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("file_name,level,row_number,column,message\n");
        for (ErpImportReportModel.IssueRow issue : collectIssues(preview)) {
            csv.append(csvField(issue.fileName())).append(',')
                    .append(csvField(issue.level())).append(',')
                    .append(csvField(issue.rowNumber() == null ? "" : String.valueOf(issue.rowNumber()))).append(',')
                    .append(csvField(issue.column())).append(',')
                    .append(csvField(issue.message())).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code erp-validation-report-20260803-153000.pdf} 꼴의 파일명. */
    public String pdfFileName(ErpImportReportModel model) {
        String prefix = TYPE_POST_COMMIT.equals(model.reportType())
                ? "erp-import-final-report" : "erp-validation-report";
        return prefix + "-" + FILE_STAMP.format(model.generatedAt()) + ".pdf";
    }

    public String csvFileName() {
        return "erp-validation-errors-" + FILE_STAMP.format(OffsetDateTime.now()) + ".csv";
    }

    // ------------------------------------------------------------------ 조립

    private static String normalizeType(String reportType) {
        if (reportType == null || reportType.isBlank()) return TYPE_PRE_COMMIT;
        String upper = reportType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TYPE_PRE_COMMIT.equals(upper) && !TYPE_POST_COMMIT.equals(upper)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "reportType은 PRE_COMMIT 또는 POST_COMMIT이어야 합니다.");
        }
        return upper;
    }

    /** 파일별로 흩어진 이슈를 한 줄기로 모으고 심각도 순(ERROR → DUPLICATE → WARNING)으로 세운다. */
    private static List<ErpImportReportModel.IssueRow> collectIssues(ErpImportDto.PreviewResponse preview) {
        List<ErpImportReportModel.IssueRow> issues = new ArrayList<>();
        for (ErpImportDto.FileAnalysis file : preview.files()) {
            for (ErpImportDto.Issue issue : file.issues()) {
                issues.add(new ErpImportReportModel.IssueRow(
                        file.fileName(), issue.level(), issue.rowNumber(),
                        issue.column() == null ? "-" : issue.column(), issue.message()));
            }
        }
        // 같은 심각도 안에서는 파일명·행 번호 순 — 사용자가 파일을 열어 위에서 아래로 고칠 수 있게.
        issues.sort(Comparator
                .comparingInt((ErpImportReportModel.IssueRow row) -> switch (row.level()) {
                    case "ERROR" -> 0;
                    case "DUPLICATE" -> 1;
                    default -> 2;
                })
                .thenComparing(ErpImportReportModel.IssueRow::fileName)
                .thenComparing(row -> row.rowNumber() == null ? Integer.MAX_VALUE : row.rowNumber()));
        return issues;
    }

    private ErpImportReportModel.Commit commitOf(String receiptToken) {
        ErpImportReceiptService.Receipt receipt = receiptService.verify(receiptToken);
        List<ErpImportReportModel.TableResultRow> results = receipt.results().stream()
                .map(row -> new ErpImportReportModel.TableResultRow(
                        row.targetTable(), ErpImportService.labelOf(row.targetTable()),
                        row.inserted(), row.updated()))
                .toList();
        return new ErpImportReportModel.Commit(
                receipt.approver(), receipt.committedAt(),
                receipt.totalInserted(), receipt.totalUpdated(), results, receipt.kgSyncWarning());
    }

    // ------------------------------------------------------------------ 그리기

    private void writeCover(Document document, ErpImportReportModel model) {
        boolean post = TYPE_POST_COMMIT.equals(model.reportType());

        Paragraph title = new Paragraph("ERP 데이터 품질검증 보고서", font(bold(), 22, INK));
        title.setSpacingAfter(6);
        document.add(title);

        Paragraph subtitle = new Paragraph(
                post ? "최종 반영 보고서" : "반영 전 검증 보고서", font(regular(), 13, MUTED));
        subtitle.setSpacingAfter(18);
        document.add(subtitle);

        PdfPTable meta = keyValueTable();
        addKeyValue(meta, "보고서 유형", post ? "최종 반영" : "반영 전 검증");
        addKeyValue(meta, "생성 시각", DISPLAY.format(model.generatedAt()));
        addKeyValue(meta, "시스템명", model.systemName());
        addKeyValue(meta, "검사 담당자", model.inspector());
        meta.setSpacingAfter(16);
        document.add(meta);

        // 반영 전 보고서를 반영 완료 증빙으로 오해하면 "올렸다고 생각했는데 DB에는 없는" 상태가 된다.
        // 표지에서 못 박아 둔다.
        if (!post) {
            document.add(noticeBox(
                    "본 보고서는 데이터베이스 반영 전 품질검사 결과입니다.\n"
                            + "현재 데이터는 운영 데이터베이스에 반영되지 않았습니다.",
                    WARN, new Color(0xFF, 0xF7, 0xED)));
        } else {
            document.add(noticeBox(
                    "본 보고서는 운영 데이터베이스 반영이 완료된 결과입니다.",
                    OK, new Color(0xEC, 0xFD, 0xF5)));
        }
    }

    private void writeOverview(Document document, ErpImportReportModel model) {
        ErpImportReportModel.Overview overview = model.overview();
        document.add(heading("검증 개요"));

        PdfPTable table = keyValueTable();
        addKeyValue(table, "파일 수", overview.fileCount() + "개");
        addKeyValue(table, "전체 행 수", number(overview.totalRows()) + "행");
        addKeyValue(table, "오류 수", number(overview.totalErrors()) + "건");
        addKeyValue(table, "경고 수", number(overview.totalWarnings()) + "건");
        addKeyValue(table, "중복 수", number(overview.totalDuplicates()) + "건");
        addKeyValue(table, "품질점수", overview.qualityScore() + " / 100");
        addKeyValue(table, "DB 반영 가능 여부", overview.committable() ? "반영 가능" : "반영 불가(오류 있음)");
        addKeyValue(table, "현재 DB 반영 상태",
                model.commit() == null ? "미반영" : "반영 완료 (" + DISPLAY.format(model.commit().committedAt()) + ")");
        table.setSpacingAfter(14);
        document.add(table);
    }

    private void writeFileResults(Document document, ErpImportReportModel model) {
        document.add(heading("파일별 검증 결과"));
        PdfPTable table = dataTable(new float[]{28, 16, 11, 11, 8, 8, 8, 10},
                "파일명", "대상 테이블", "크기", "행 수", "오류", "경고", "중복", "판정");
        for (ErpImportReportModel.FileRow row : model.files()) {
            addCell(table, row.fileName(), Element.ALIGN_LEFT, INK);
            addCell(table, row.targetLabel(), Element.ALIGN_LEFT, INK);
            addCell(table, fileSize(row.sizeBytes()), Element.ALIGN_RIGHT, INK);
            addCell(table, number(row.rowCount()), Element.ALIGN_RIGHT, INK);
            addCell(table, number(row.errorCount()), Element.ALIGN_RIGHT, row.errorCount() > 0 ? DANGER : MUTED);
            addCell(table, number(row.warningCount()), Element.ALIGN_RIGHT, row.warningCount() > 0 ? WARN : MUTED);
            addCell(table, number(row.duplicateCount()), Element.ALIGN_RIGHT, MUTED);
            addCell(table, resultLabel(row.result()), Element.ALIGN_CENTER, resultColor(row.result()));
        }
        table.setSpacingAfter(14);
        document.add(table);
    }

    private void writeTableEstimates(Document document, ErpImportReportModel model) {
        if (model.tableEstimates().isEmpty()) return;
        document.add(heading("테이블별 예상 반영 결과"));
        PdfPTable table = dataTable(new float[]{40, 35, 25}, "대상 테이블", "표시명", "예상 반영 행 수");
        for (ErpImportReportModel.TableEstimate row : model.tableEstimates()) {
            addCell(table, row.targetTable(), Element.ALIGN_LEFT, INK);
            addCell(table, row.label(), Element.ALIGN_LEFT, INK);
            addCell(table, number(row.rowCount()), Element.ALIGN_RIGHT, INK);
        }
        table.setSpacingAfter(14);
        document.add(table);
    }

    private void writeMappings(Document document, ErpImportReportModel model) {
        if (model.mappings().isEmpty()) return;
        document.add(heading("스키마 매핑 결과"));
        PdfPTable table = dataTable(new float[]{18, 15, 15, 20, 8, 10, 14},
                "파일명", "원본 컬럼", "대상 필드", "필드 설명", "필수", "매핑 상태", "샘플 값");
        for (ErpImportReportModel.MappingRow row : model.mappings()) {
            addCell(table, row.fileName(), Element.ALIGN_LEFT, MUTED);
            addCell(table, row.sourceColumn(), Element.ALIGN_LEFT, INK);
            addCell(table, row.targetField(), Element.ALIGN_LEFT, INK);
            addCell(table, row.description(), Element.ALIGN_LEFT, MUTED);
            addCell(table, row.required() ? "필수" : "선택", Element.ALIGN_CENTER, MUTED);
            addCell(table, mappingLabel(row.status()), Element.ALIGN_CENTER, mappingColor(row.status()));
            addCell(table, row.sample() == null ? "-" : row.sample(), Element.ALIGN_LEFT, MUTED);
        }
        table.setSpacingAfter(14);
        document.add(table);
    }

    private void writeIssues(Document document, ErpImportReportModel model) {
        document.add(heading("오류 및 경고"));
        if (model.issues().isEmpty()) {
            Paragraph none = new Paragraph("검출된 오류·경고가 없습니다.", font(regular(), 10, MUTED));
            none.setSpacingAfter(14);
            document.add(none);
            return;
        }

        PdfPTable table = dataTable(new float[]{20, 10, 8, 16, 46},
                "파일명", "수준", "행 번호", "컬럼", "메시지");
        for (ErpImportReportModel.IssueRow row : model.issues()) {
            addCell(table, row.fileName(), Element.ALIGN_LEFT, MUTED);
            addCell(table, levelLabel(row.level()), Element.ALIGN_CENTER, levelColor(row.level()));
            addCell(table, row.rowNumber() == null ? "-" : String.valueOf(row.rowNumber()),
                    Element.ALIGN_RIGHT, MUTED);
            addCell(table, row.column(), Element.ALIGN_LEFT, INK);
            addCell(table, row.message(), Element.ALIGN_LEFT, INK);
        }
        table.setSpacingAfter(8);
        document.add(table);

        if (model.totalIssueCount() > model.issues().size()) {
            Paragraph note = new Paragraph(
                    "전체 오류 " + number(model.totalIssueCount()) + "건 중 "
                            + model.issues().size() + "건을 표시합니다.\n"
                            + "전체 오류 내역은 오류 목록 CSV를 이용해 주십시오.",
                    font(regular(), 9, WARN));
            note.setSpacingAfter(14);
            document.add(note);
        }
    }

    private void writeCommit(Document document, ErpImportReportModel model) {
        ErpImportReportModel.Commit commit = model.commit();
        if (commit == null) return;

        document.add(heading("최종 반영 결과"));
        PdfPTable meta = keyValueTable();
        addKeyValue(meta, "승인자", commit.approver());
        addKeyValue(meta, "승인 시각", DISPLAY.format(commit.committedAt()));
        addKeyValue(meta, "DB 반영 완료 시각", DISPLAY.format(commit.committedAt()));
        addKeyValue(meta, "전체 삽입 건수", number(commit.totalInserted()) + "건");
        addKeyValue(meta, "전체 갱신 건수", number(commit.totalUpdated()) + "건");
        addKeyValue(meta, "KG 동기화 결과",
                commit.kgSyncWarning() == null ? "정상 동기화" : "동기화 실패");
        meta.setSpacingAfter(12);
        document.add(meta);

        if (!commit.results().isEmpty()) {
            PdfPTable table = dataTable(new float[]{34, 28, 19, 19},
                    "대상 테이블", "표시명", "삽입", "갱신");
            for (ErpImportReportModel.TableResultRow row : commit.results()) {
                addCell(table, row.targetTable(), Element.ALIGN_LEFT, INK);
                addCell(table, row.label(), Element.ALIGN_LEFT, INK);
                addCell(table, number(row.inserted()), Element.ALIGN_RIGHT, INK);
                addCell(table, number(row.updated()), Element.ALIGN_RIGHT, INK);
            }
            table.setSpacingAfter(12);
            document.add(table);
        }

        // DB는 들어갔는데 KG만 옛 값으로 남은 상태다. "반영 실패"로 읽히면 같은 파일을 또 올린다.
        if (commit.kgSyncWarning() != null) {
            document.add(noticeBox(
                    "ERP 데이터는 정상 반영됐지만 KG 동기화에 실패했습니다.\n"
                            + "지식그래프에는 이전 데이터가 남아 있을 수 있습니다.\n"
                            + "사유: " + commit.kgSyncWarning(),
                    WARN, new Color(0xFF, 0xF7, 0xED)));
        }
    }

    // ------------------------------------------------------------ 그리기 도우미

    private Paragraph heading(String text) {
        Paragraph heading = new Paragraph(text, font(bold(), 13, INK));
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(8);
        return heading;
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

    /** 푸터 이벤트가 생성자에서 필요로 해서 따로 둔다. */
    private BaseFont baseRegular() {
        return regular();
    }

    private static BaseFont load(String resource) {
        try (InputStream stream = ErpImportReportService.class.getResourceAsStream(resource)) {
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

    private static String number(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    private static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String resultLabel(String result) {
        return switch (result) {
            case "SUCCESS" -> "정상";
            case "WARNING" -> "경고";
            default -> "오류";
        };
    }

    private static Color resultColor(String result) {
        return switch (result) {
            case "SUCCESS" -> OK;
            case "WARNING" -> WARN;
            default -> DANGER;
        };
    }

    private static String levelLabel(String level) {
        return switch (level) {
            case "ERROR" -> "오류";
            case "DUPLICATE" -> "중복";
            default -> "경고";
        };
    }

    private static Color levelColor(String level) {
        return switch (level) {
            case "ERROR" -> DANGER;
            case "DUPLICATE" -> MUTED;
            default -> WARN;
        };
    }

    private static String mappingLabel(String status) {
        return switch (status) {
            case "MAPPED" -> "매핑됨";
            case "IGNORED" -> "무시됨";
            default -> "누락";
        };
    }

    private static Color mappingColor(String status) {
        return switch (status) {
            case "MAPPED" -> OK;
            case "IGNORED" -> MUTED;
            default -> DANGER;
        };
    }

    /**
     * CSV 한 칸. 수식 주입을 막고 나서 따옴표로 감싼다 — 순서가 중요하다. 감싼 뒤에 접두사를
     * 붙이면 따옴표 안쪽이 아니라 바깥에 붙어 CSV 구조가 깨진다.
     */
    private static String csvField(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** 모든 쪽 하단에 "n / m 페이지 · 생성 시각". 감사 문서라 쪽이 빠졌는지 보여야 한다. */
    private static final class Footer extends PdfPageEventHelper {
        private final BaseFont font;
        private final String generatedAt;
        private PdfTemplate totalPages;
        private int lastPage;

        private Footer(BaseFont font, OffsetDateTime generatedAt) {
            this.font = font;
            this.generatedAt = DISPLAY.format(generatedAt);
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
                    new Phrase("생성 " + generatedAt, new Font(font, 8, Font.NORMAL, MUTED)),
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
            totalPages.showText(lastPage + " 페이지");
            totalPages.endText();
        }
    }
}

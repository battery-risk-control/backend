package com.example.batteryrisk;

import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.dto.ErpImportReportModel;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.service.ErpAdminService;
import com.example.batteryrisk.service.ErpImportReceiptService;
import com.example.batteryrisk.service.ErpImportReportService;
import com.example.batteryrisk.service.ErpImportService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ERP 검증 보고서(PDF·오류 CSV) 검증.
 *
 * <p>가장 중요한 두 가지를 실제로 확인한다. 하나는 <b>한글이 PDF에서 읽히는지</b> — 폰트를
 * 임베드하지 않으면 뷰어에서 네모로 나오는데, 바이트 길이만 재는 테스트는 그걸 잡지 못한다.
 * 그래서 만든 PDF를 다시 파싱해 글자를 꺼내 본다. 다른 하나는 <b>최종 보고서의 숫자를 요청자가
 * 정할 수 없는지</b>다.
 */
class ErpImportReportServiceTest {
    private final ErpImportReceiptService receipts =
            new ErpImportReceiptService("test-secret-key-please-override-32bytes-minimum!");
    private final ErpImportService importService = new ErpImportService(
            mock(ErpAdminService.class), mock(ErpRepository.class), receipts, 52_428_800L);
    private final ErpImportReportService service =
            new ErpImportReportService(importService, receipts, "배터리 공급망 리스크 관리 시스템");

    // ------------------------------------------------------------------ PDF

    /** 폰트 임베드가 실제로 됐는지 — 만든 PDF에서 한글을 도로 꺼낼 수 있어야 한다. */
    @Test
    void koreanTextIsReadableBackOutOfTheGeneratedPdf() throws Exception {
        byte[] pdf = service.renderPdf(
                service.buildModel(List.of(goodMaterials()), "PRE_COMMIT", null));

        assertTrue(pdf.length > 1000, "PDF가 비어 있습니다");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        String text = extractAllText(pdf);
        assertTrue(text.contains("ERP 데이터 품질검증 보고서"), () -> "표지 제목이 없습니다: " + text);
        assertTrue(text.contains("검증 개요"), "검증 개요 절이 없습니다");
        assertTrue(text.contains("자재 마스터"), "대상 테이블 한글 라벨이 없습니다");
        assertTrue(text.contains("배터리 공급망 리스크 관리 시스템"), "시스템명이 없습니다");
    }

    /** 반영 전 보고서를 "반영 완료 증빙"으로 오해하면 안 된다. 표지에 못 박은 문구를 확인한다. */
    @Test
    void preCommitReportStatesNothingWasWrittenToTheDatabase() throws Exception {
        String text = extractAllText(service.renderPdf(
                service.buildModel(List.of(goodMaterials()), "PRE_COMMIT", null)));

        assertTrue(text.contains("운영 데이터베이스에 반영되지 않았습니다"),
                () -> "미반영 안내가 없습니다: " + text);
        assertTrue(text.contains("미반영"), "DB 반영 상태가 '미반영'으로 찍히지 않았습니다");
    }

    /** 오류가 많아도 PDF는 상위 100건에서 끊고, 끊었다는 사실과 CSV 안내를 함께 적어야 한다. */
    @Test
    void pdfCapsIssuesAtHundredAndSaysSoWithCsvGuidance() throws Exception {
        MultipartFile many = materialsWithBadBooleanRows(300);

        ErpImportReportModel model = service.buildModel(List.of(many), "PRE_COMMIT", null);
        assertEquals(100, model.issues().size());
        assertTrue(model.totalIssueCount() >= 300,
                "전체 이슈 수가 잘려 있습니다: " + model.totalIssueCount());

        String text = extractAllText(service.renderPdf(model));
        assertTrue(text.contains("100건을 표시합니다"), () -> "일부 표시 안내가 없습니다");
        assertTrue(text.contains("오류 목록 CSV"), "CSV 안내가 없습니다");
    }

    /** 표가 여러 쪽으로 넘어가면 각 쪽에 머리글이 다시 나와야 읽을 수 있다. */
    @Test
    void longTablesSpanPagesAndRepeatTheirHeaderRow() throws Exception {
        byte[] pdf = service.renderPdf(
                service.buildModel(List.of(materialsWithBadBooleanRows(300)), "PRE_COMMIT", null));

        PdfReader reader = new PdfReader(pdf);
        assertTrue(reader.getNumberOfPages() > 1,
                "여러 쪽이 나와야 합니다: " + reader.getNumberOfPages() + "쪽");

        // 오류 표의 머리글("메시지")이 한 쪽에만 있으면 헤더 반복이 안 된 것이다.
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        int pagesWithIssueHeader = 0;
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            if (extractor.getTextFromPage(page).contains("메시지")) pagesWithIssueHeader++;
        }
        reader.close();
        assertTrue(pagesWithIssueHeader > 1,
                "머리글이 반복되지 않았습니다(발견된 쪽 수: " + pagesWithIssueHeader + ")");
    }

    /** 푸터에 쪽 번호가 찍혀야 감사 문서로서 낙장을 알 수 있다. */
    @Test
    void everyPageCarriesAPageNumberFooter() throws Exception {
        byte[] pdf = service.renderPdf(
                service.buildModel(List.of(materialsWithBadBooleanRows(300)), "PRE_COMMIT", null));

        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            String text = extractor.getTextFromPage(page);
            assertTrue(text.contains("페이지"), page + "쪽에 쪽 번호가 없습니다");
            assertTrue(text.contains("생성 "), page + "쪽에 생성 시각이 없습니다");
        }
        reader.close();
    }

    @Test
    void pdfFileNameDiffersBetweenPreAndPostCommitReports() {
        ErpImportReportModel pre = service.buildModel(List.of(goodMaterials()), "PRE_COMMIT", null);
        assertTrue(service.pdfFileName(pre).matches("erp-validation-report-\\d{8}-\\d{6}\\.pdf"),
                service.pdfFileName(pre));

        ErpImportReportModel post = service.buildModel(
                List.of(goodMaterials()), "POST_COMMIT", signedReceipt());
        assertTrue(service.pdfFileName(post).matches("erp-import-final-report-\\d{8}-\\d{6}\\.pdf"),
                service.pdfFileName(post));
    }

    @Test
    void unknownReportTypeIsRejected() {
        assertThrows(BusinessException.class,
                () -> service.buildModel(List.of(goodMaterials()), "WHATEVER", null));
    }

    // -------------------------------------------------------------- 영수증

    /** 최종 보고서의 승인자·건수는 서명된 영수증에서만 온다. */
    @Test
    void postCommitReportRendersTheSignedCommitFacts() throws Exception {
        ErpImportReportModel model = service.buildModel(
                List.of(goodMaterials()), "POST_COMMIT", signedReceipt());

        assertNotNull(model.commit());
        assertEquals(7, model.commit().totalInserted());
        assertEquals(3, model.commit().totalUpdated());
        // 영수증은 테이블 키만 싣는다 — 라벨은 서버가 다시 붙여야 한다.
        assertEquals("자재 마스터", model.commit().results().get(0).label());

        String text = extractAllText(service.renderPdf(model));
        assertTrue(text.contains("최종 반영 결과"), "최종 반영 절이 없습니다");
        assertTrue(text.contains("반영 완료"), "DB 반영 상태가 '반영 완료'로 찍히지 않았습니다");
    }

    /** 영수증을 한 글자라도 고치면 보고서를 만들 수 없어야 한다 — 이게 없으면 숫자를 지어낼 수 있다. */
    @Test
    void tamperedReceiptIsRefused() {
        String receipt = signedReceipt();
        String tampered = receipt.substring(0, receipt.length() - 4) + "AAAA";

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.buildModel(List.of(goodMaterials()), "POST_COMMIT", tampered));
        assertTrue(thrown.getMessage().contains("영수증"), thrown.getMessage());
    }

    @Test
    void postCommitReportWithoutAReceiptIsRefused() {
        assertThrows(BusinessException.class,
                () -> service.buildModel(List.of(goodMaterials()), "POST_COMMIT", null));
    }

    /** 서명·검증 왕복에서 값이 그대로 살아남아야 한다. */
    @Test
    void receiptRoundTripsEveryCommitFact() {
        OffsetDateTime committedAt = OffsetDateTime.parse("2026-08-03T15:30:00+09:00");
        String token = receipts.sign(committedAt, 12, 5,
                List.of(new ErpImportDto.TableResult("suppliers", "공급사", 12, 5)),
                "kg_service 연결 실패");

        ErpImportReceiptService.Receipt receipt = receipts.verify(token);

        assertEquals(committedAt.toInstant(), receipt.committedAt().toInstant());
        assertEquals(12, receipt.totalInserted());
        assertEquals(5, receipt.totalUpdated());
        assertEquals("suppliers", receipt.results().get(0).targetTable());
        assertEquals("kg_service 연결 실패", receipt.kgSyncWarning());
    }

    /** KG 경고는 "DB 반영 실패"가 아니라는 걸 보고서가 구분해서 적어야 한다. */
    @Test
    void kgSyncWarningIsReportedSeparatelyFromDatabaseSuccess() throws Exception {
        String token = receipts.sign(OffsetDateTime.now(), 4, 0,
                List.of(new ErpImportDto.TableResult("materials", "자재 마스터", 4, 0)),
                "kg_service 응답 없음");

        String text = extractAllText(service.renderPdf(
                service.buildModel(List.of(goodMaterials()), "POST_COMMIT", token)));

        assertTrue(text.contains("ERP 데이터는 정상 반영됐지만 KG 동기화에 실패했습니다"),
                () -> "KG 경고 문구가 없습니다: " + text);
        assertTrue(text.contains("이전 데이터가 남아 있을 수 있습니다"), "KG 영향 설명이 없습니다");
    }

    // ------------------------------------------------------------------ CSV

    /**
     * 오류 CSV는 화면 표시 한도(파일당 50건)에 걸리면 안 된다 — "전체 오류 내역"이 존재 이유다.
     */
    @Test
    void errorCsvKeepsEveryIssueBeyondTheScreenLimit() {
        MultipartFile many = materialsWithBadBooleanRows(120);

        // 화면용 응답은 50건에서 자르고 "더 있다"는 안내 한 줄을 붙인다.
        ErpImportDto.FileAnalysis onScreen = importService.preview(List.of(many)).files().get(0);
        assertEquals(51, onScreen.issues().size());

        String csv = new String(service.renderErrorCsv(List.of(many)), StandardCharsets.UTF_8);
        long errorRows = csv.lines().filter(line -> line.contains("\"ERROR\"")).count();
        assertEquals(120, errorRows, "CSV가 잘렸습니다");
    }

    /**
     * 엑셀에서 열었을 때 셀이 수식으로 실행되면 안 된다.
     *
     * <p>주입이 실제로 들어오는 자리는 {@code column} 칸이다 — 사용자가 올린 파일의 헤더가
     * 가공 없이 그대로 실린다. 반대로 {@code message} 칸은 항상 우리가 쓴 문구로 시작하므로
     * 원본 값이 그 안에 인용돼 있어도 셀 자체는 수식이 되지 않는다.
     */
    @Test
    void formulaLikeColumnNamesAreNeutralisedInTheCsv() {
        MultipartFile injected = csv("01_materials.csv", """
                material_id,material_code,material_name,material_category,base_unit,criticality,active,erp_group_code,=cmd|'/c calc'!A1
                MAT-A,RM-001,Cathode,LITHIUM,KG,HIGH,true,BATT-01,x
                """);

        String content = new String(service.renderErrorCsv(List.of(injected)), StandardCharsets.UTF_8);

        assertTrue(content.contains("\"'=cmd|'/c calc'!A1\""),
                () -> "수식 접두사가 붙지 않았습니다: " + content);
        assertFalse(content.contains(",\"=cmd"), "수식이 그대로 실행 가능한 형태로 남아 있습니다");
    }

    @Test
    void errorCsvStartsWithBomAndTheAgreedHeader() {
        String content = new String(service.renderErrorCsv(List.of(goodMaterials())), StandardCharsets.UTF_8);

        assertTrue(content.startsWith("﻿"), "엑셀 한글 깨짐 방지용 BOM이 없습니다");
        assertTrue(content.contains("file_name,level,row_number,column,message"), content);
    }

    // ---------------------------------------------------------------- 도우미

    private String signedReceipt() {
        return receipts.sign(
                OffsetDateTime.parse("2026-08-03T15:45:00+09:00"), 7, 3,
                List.of(new ErpImportDto.TableResult("materials", "자재 마스터", 7, 3)),
                null);
    }

    private static String extractAllText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        reader.close();
        return text.toString();
    }

    private static MultipartFile goodMaterials() {
        return csv("01_materials.csv", """
                material_id,material_code,material_name,material_category,base_unit,criticality,active,erp_group_code
                MAT-A,RM-001,양극재,LITHIUM,KG,HIGH,true,BATT-01
                MAT-B,RM-002,음극재,GRAPHITE,KG,MEDIUM,true,BATT-02
                """);
    }

    /** {@code active}에 불리언이 아닌 값을 넣어 행마다 오류가 하나씩 나게 만든다. */
    private static MultipartFile materialsWithBadBooleanRows(int rows) {
        StringBuilder body = new StringBuilder(
                "material_id,material_code,material_name,material_category,base_unit,criticality,active,erp_group_code\n");
        for (int index = 0; index < rows; index++) {
            body.append("MAT-").append(index).append(",RM-").append(index)
                    .append(",자재").append(index).append(",LITHIUM,KG,HIGH,maybe,BATT-01\n");
        }
        return csv("01_materials.csv", body.toString());
    }

    private static MultipartFile csv(String fileName, String body) {
        return new MockMultipartFile("files", fileName, "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    /** 반영 전 보고서에는 승인 정보가 붙으면 안 된다. */
    @Test
    void preCommitModelHasNoCommitSection() {
        assertNull(service.buildModel(List.of(goodMaterials()), "PRE_COMMIT", null).commit());
    }
}

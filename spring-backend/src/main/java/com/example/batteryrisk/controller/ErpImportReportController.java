package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ErpImportReportModel;
import com.example.batteryrisk.service.ErpImportReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ERP 적재 검증 보고서 다운로드 API.
 *
 * <p>{@link ErpImportController}와 같은 파일을 받아 <b>같은 검증</b>을 돌린 결과를 문서로 낸다.
 * 검증 규칙을 여기서 따로 두지 않는 게 핵심이다 — 보고서 전용 규칙이 생기면 화면에서는 통과인데
 * 보고서에는 오류로 찍히는(혹은 그 반대) 상황이 만들어진다.
 *
 * <p>{@link ErpImportController}와 마찬가지로 구매팀 전용이다. 보고서에는 어떤 자재를 어느
 * 공급사에서 얼마나 들여오는지가 그대로 드러난다.
 *
 * <p>응답이 파일이라 다른 API와 달리 {@code ApiResponse} Envelope을 쓰지 않는다. 대신 실패는
 * 평소처럼 JSON 오류로 나가므로, 프론트는 응답의 Content-Type을 보고 갈라야 한다.
 */
@RestController
@RequestMapping("/api/v1/erp/imports")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('PURCHASING')")
public class ErpImportReportController {
    private final ErpImportReportService service;

    public ErpImportReportController(ErpImportReportService service) {
        this.service = service;
    }

    @Operation(
            summary = "ERP 검증 보고서 PDF",
            description = "올린 CSV를 분석과 같은 규칙으로 다시 검증해 PDF 보고서를 만듭니다. "
                    + "reportType=POST_COMMIT이면 반영 시 발급된 receipt가 필요하며, 승인자·반영 건수는 "
                    + "그 서명된 값에서만 가져옵니다(요청 본문의 숫자는 쓰지 않습니다).")
    @PostMapping(
            value = "/report",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> report(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "reportType", required = false) String reportType,
            /** 반영 시 {@code /commit}이 돌려준 서명 문자열. POST_COMMIT일 때만 쓴다. */
            @RequestParam(value = "receipt", required = false) String receipt) {
        ErpImportReportModel model = service.buildModel(files, reportType, receipt);
        byte[] pdf = service.renderPdf(model);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(service.pdfFileName(model)))
                .body(pdf);
    }

    @Operation(
            summary = "전체 오류 목록 CSV",
            description = "PDF가 상위 100건만 싣기 때문에 전체 오류를 따로 내려받는 작업용 파일입니다. "
                    + "엑셀에서 바로 열 수 있도록 BOM을 붙이고 수식 주입을 이스케이프합니다.")
    @PostMapping(
            value = "/errors.csv",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/csv")
    public ResponseEntity<byte[]> errorCsv(@RequestPart("files") List<MultipartFile> files) {
        byte[] csv = service.renderErrorCsv(files);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(service.csvFileName()))
                .body(csv);
    }

    /**
     * 파일명은 서버가 만든 ASCII 문자열이라 그대로 써도 되지만, 규격대로 {@code filename*}을 함께
     * 낸다 — 나중에 한글 파일명을 쓰게 될 때 여기만 고치면 되도록.
     */
    private static String attachment(String fileName) {
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }
}

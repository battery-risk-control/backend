package com.example.batteryrisk;

import com.example.batteryrisk.controller.ErpImportReportController;
import com.example.batteryrisk.dto.ErpImportReportModel;
import com.example.batteryrisk.exception.GlobalExceptionHandler;
import com.example.batteryrisk.service.ErpImportReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보고서 다운로드 API의 껍데기 검증 — 권한과 응답 헤더.
 *
 * <p>보고서 내용은 {@link ErpImportReportServiceTest}가 본다. 여기서 확인할 건 두 가지다.
 * 구매팀이 아니면 못 받는가, 그리고 브라우저가 파일로 저장하게 하는 헤더가 붙는가.
 */
@WebMvcTest(controllers = {ErpImportReportController.class, GlobalExceptionHandler.class})
class ErpImportReportControllerTest {
    /**
     * {@code @WebMvcTest}는 웹 계층만 올리느라 {@code SecurityConfig}를 빼놓는데, 거기에
     * {@code @EnableMethodSecurity}가 달려 있다. 그대로 두면 {@code @PreAuthorize}가 아예 동작하지
     * 않아서 "권한 없는 사용자를 막는다"는 테스트가 통과해도 아무것도 증명하지 못한다.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired MockMvc mockMvc;
    @MockBean ErpImportReportService reportService;

    private static final MockMultipartFile FILE =
            new MockMultipartFile("files", "01_materials.csv", "text/csv", "material_id\nMAT-A\n".getBytes());

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void returnsPdfAsAnAttachmentWithTheAgreedFileName() throws Exception {
        when(reportService.buildModel(anyList(), nullable(String.class), nullable(String.class)))
                .thenReturn(model());
        when(reportService.renderPdf(any())).thenReturn("%PDF-1.4 fake".getBytes());
        when(reportService.pdfFileName(any())).thenReturn("erp-validation-report-20260803-153000.pdf");

        mockMvc.perform(multipart("/api/v1/erp/imports/report")
                        .file(FILE)
                        .with(csrf())
                        .param("reportType", "PRE_COMMIT"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "attachment; filename=\"erp-validation-report-20260803-153000.pdf\"")));
    }

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void returnsErrorCsvAsAnAttachment() throws Exception {
        when(reportService.renderErrorCsv(anyList())).thenReturn("file_name,level\n".getBytes());
        when(reportService.csvFileName()).thenReturn("erp-validation-errors-20260803-153000.csv");

        mockMvc.perform(multipart("/api/v1/erp/imports/errors.csv").file(FILE).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("erp-validation-errors-20260803-153000.csv")));
    }

    /** 보고서에는 어떤 자재를 어디서 얼마나 들여오는지가 그대로 드러난다. 구매팀 밖으로 나가면 안 된다. */
    @Test
    @WithMockUser(username = "planner", roles = "PLANNING")
    void blocksRolesOtherThanPurchasing() throws Exception {
        mockMvc.perform(multipart("/api/v1/erp/imports/report")
                        .file(FILE)
                        .with(csrf())
                        .param("reportType", "PRE_COMMIT"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "planner", roles = "PLANNING")
    void blocksErrorCsvForRolesOtherThanPurchasing() throws Exception {
        mockMvc.perform(multipart("/api/v1/erp/imports/errors.csv").file(FILE).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static ErpImportReportModel model() {
        return new ErpImportReportModel(
                "PRE_COMMIT", OffsetDateTime.now(), "테스트 시스템", "purchaser",
                new ErpImportReportModel.Overview(1, 1, 0, 0, 0, 100, true),
                List.of(), List.of(), List.of(), List.of(), 0, null);
    }
}

package com.example.batteryrisk;

import com.example.batteryrisk.controller.DocumentController;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler;
import com.example.batteryrisk.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {DocumentController.class, GlobalExceptionHandler.class})
class DocumentControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean DocumentService documentService;

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void uploadsDocumentWithSnakeCaseResponse() throws Exception {
        when(documentService.upload(any(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(new DocumentDto.UploadResponse(
                        "DOC-ABC", 1L, 2L, 3L, "LTA", "contract.txt", "hash", 1,
                        "COMPLETED", "MOCK_TOKEN_HASH", "mock-v1", false, true,
                        Instant.parse("2026-07-21T00:00:00Z")));

        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", "price clause".getBytes());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .with(csrf())
                        .param("contract_id", "1")
                        .param("supplier_id", "2")
                        .param("material_id", "3")
                        .param("document_type", "LTA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.document_id").value("DOC-ABC"))
                .andExpect(jsonPath("$.data.processing_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.embedding_type").value("MOCK_TOKEN_HASH"))
                .andExpect(jsonPath("$.data.embedding_version").value("mock-v1"))
                .andExpect(jsonPath("$.data.documentId").doesNotExist());
    }
}

package com.example.batteryrisk;

import com.example.batteryrisk.controller.RagController;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.exception.GlobalExceptionHandler;
import com.example.batteryrisk.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RagController.class, GlobalExceptionHandler.class})
class RagControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean RagService ragService;

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void exposesSnakeCaseSearchResponse() throws Exception {
        RagDto.SearchItem item = new RagDto.SearchItem(
                "DOC-1", 1L, 2L, 3L, null, null, "LTA", 0, 1,
                "리튬 가격 조정 조항",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                0.75, "MOCK_TOKEN_HASH", "mock-v1", true);
        when(ragService.search(any())).thenReturn(new RagDto.SearchResult(List.of(item), true));

        mockMvc.perform(post("/api/v1/rag/search")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"query":"리튬 가격 조정","filters":{"contract_id":1},"top_k":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.results[0].document_id").value("DOC-1"))
                .andExpect(jsonPath("$.data.results[0].similarity_score").value(0.75))
                .andExpect(jsonPath("$.data.results[0].embedding_type").value("MOCK_TOKEN_HASH"))
                .andExpect(jsonPath("$.data.results[0].documentId").doesNotExist());
    }

    @Test
    @WithMockUser(username = "purchaser", roles = "PURCHASING")
    void rejectsBlankQuery() throws Exception {
        mockMvc.perform(post("/api/v1/rag/search")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"query":"   ","filters":{"contract_id":1},"top_k":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}

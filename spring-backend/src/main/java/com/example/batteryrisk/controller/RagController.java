package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
@SecurityRequirement(name = "bearerAuth")
public class RagController {
    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @Operation(
            summary = "계약 문서 근거 검색",
            description = "Spring Boot가 FastAPI ChromaDB 검색을 호출하고 근거 청크를 반환합니다.")
    @PostMapping("/search")
    public ApiResponse<RagDto.SearchResult> search(
            @Valid @RequestBody RagDto.SearchRequest request) {
        return ApiResponse.ok(ragService.search(request));
    }
}

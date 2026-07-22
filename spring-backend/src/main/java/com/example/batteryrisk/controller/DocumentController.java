package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "RAG 문서 업로드", description = "PDF/TXT 문서를 접수하여 FastAPI 문서 처리 API로 전달합니다.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentDto.UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("contract_id") Long contractId,
            @RequestParam("supplier_id") Long supplierId,
            @RequestParam("material_id") Long materialId,
            @RequestParam(name = "document_type", defaultValue = "LTA") String documentType) {
        return ApiResponse.ok(
                documentService.upload(file, contractId, supplierId, materialId, documentType));
    }

    @Operation(summary = "문서 처리 상태 조회", description = "PostgreSQL에 영구 저장된 문서 Metadata와 처리 상태를 조회합니다.")
    @GetMapping("/{document_id}")
    public ApiResponse<DocumentDto.DocumentStatusResponse> get(
            @PathVariable("document_id") String documentId) {
        return ApiResponse.ok(documentService.get(documentId));
    }
}

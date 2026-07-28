package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.SupplierDto;
import com.example.batteryrisk.service.SupplierQualificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/suppliers")
@SecurityRequirement(name = "bearerAuth")
public class SupplierController {
    private final SupplierQualificationService supplierQualificationService;

    public SupplierController(SupplierQualificationService supplierQualificationService) {
        this.supplierQualificationService = supplierQualificationService;
    }

    @Operation(
            summary = "F9: 자재 대분류별 적격 공급사 조회",
            description = "APPROVED/ACTIVE/FEOC 적합 등 필수조건을 통과한 공급사만 반환합니다. "
                    + "F3(FastAPI)가 이 결과를 그대로 비교·추천에 씁니다(새 후보를 만들지 않음)."
    )
    @GetMapping("/qualified")
    public ApiResponse<SupplierDto.QualifiedSuppliersResponse> qualified(
            @RequestParam("material_category") String materialCategory) {
        return ApiResponse.ok(supplierQualificationService.findQualifiedSuppliers(materialCategory));
    }
}

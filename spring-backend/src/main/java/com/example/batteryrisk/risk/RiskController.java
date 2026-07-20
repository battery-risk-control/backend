package com.example.batteryrisk.risk;

import com.example.batteryrisk.common.ApiResponse;
import com.example.batteryrisk.common.ImpactDomain;
import com.example.batteryrisk.common.Severity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/risks")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping
    public ApiResponse<PageResponse<RiskListItemResponse>> getRisks(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) ImpactDomain impactDomain,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<RiskListItemResponse> response =
                riskService.getRisks(
                        severity,
                        impactDomain,
                        materialId,
                        supplierId,
                        page,
                        size
                );

        return ApiResponse.ok(response);
    }

    @GetMapping("/{riskId}")
    public ApiResponse<RiskDetailResponse> getRisk(
            @PathVariable long riskId
    ) {
        return ApiResponse.ok(
                riskService.getRisk(riskId)
        );
    }
}

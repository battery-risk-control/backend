package com.example.batteryrisk.contract;

import com.example.batteryrisk.common.ApiResponse;
import com.example.batteryrisk.risk.PageResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping
    public ApiResponse<PageResponse<ContractListItemResponse>> getContracts(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<ContractListItemResponse> response =
                contractService.getContracts(
                        supplierId,
                        materialId,
                        status,
                        page,
                        size
                );

        return ApiResponse.ok(response);
    }

    @GetMapping("/{contractId}")
    public ApiResponse<ContractDetailResponse> getContract(
            @PathVariable long contractId
    ) {
        return ApiResponse.ok(
                contractService.getContract(contractId)
        );
    }
}
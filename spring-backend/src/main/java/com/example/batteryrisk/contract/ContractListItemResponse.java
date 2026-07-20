package com.example.batteryrisk.contract;

import java.time.LocalDate;

public record ContractListItemResponse(
        long contractId,
        String contractNumber,
        long supplierId,
        String supplierName,
        long materialId,
        String materialName,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean documentUploaded
) {
}
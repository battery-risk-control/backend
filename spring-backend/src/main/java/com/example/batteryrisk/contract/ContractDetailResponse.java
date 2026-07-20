package com.example.batteryrisk.contract;

import com.example.batteryrisk.common.ProcessingStatus;

import java.time.LocalDate;
import java.util.List;

public record ContractDetailResponse(
        long contractId,
        String contractNumber,
        long supplierId,
        String supplierName,
        long materialId,
        String materialName,
        LocalDate startDate,
        LocalDate endDate,
        String currency,
        double basePrice,
        String status,
        ProcessingStatus documentProcessingStatus,
        List<ClauseResponse> clauses
) {
    public record ClauseResponse(
            long clauseId,
            String clauseType,
            String title,
            String summary,
            Double thresholdValue,
            String thresholdUnit
    ) {}
}

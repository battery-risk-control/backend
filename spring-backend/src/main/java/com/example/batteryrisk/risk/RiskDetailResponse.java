package com.example.batteryrisk.risk;

import com.example.batteryrisk.common.EvidenceType;
import com.example.batteryrisk.common.ImpactDomain;
import com.example.batteryrisk.common.Severity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record RiskDetailResponse(
        long riskId,
        String title,
        Source source,
        Material material,
        Supplier supplier,
        Analysis analysis,
        Inventory inventory,
        OffsetDateTime detectedAt
) {
    public record Source(String sourceName, String articleUrl, OffsetDateTime publishedAt) {}
    public record Material(long materialId, String materialName, boolean isCriticalMineral) {}
    public record Supplier(long supplierId, String supplierName, String countryCode, boolean feocStatus) {}
    public record Analysis(
            ImpactDomain impactDomain,
            double impactDomainConfidence,
            Severity severity,
            double severityScore,
            EvidenceType evidenceType,
            List<String> reasonCodes
    ) {}
    public record Inventory(
            double currentQuantity,
            String unit,
            int stockDays,
            int safetyStockDays,
            LocalDate expectedInboundDate
    ) {}
}

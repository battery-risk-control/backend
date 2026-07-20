package com.example.batteryrisk.risk;

import com.example.batteryrisk.common.EvidenceType;
import com.example.batteryrisk.common.ImpactDomain;
import com.example.batteryrisk.common.Severity;

import java.time.OffsetDateTime;

public record RiskListItemResponse(
        long riskId,
        String title,
        long materialId,
        String materialName,
        long supplierId,
        String supplierName,
        String countryCode,
        ImpactDomain impactDomain,
        Severity severity,
        double severityScore,
        int stockDays,
        EvidenceType evidenceType,
        OffsetDateTime detectedAt
) {}

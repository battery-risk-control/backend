package com.example.batteryrisk.dashboard;

import java.time.OffsetDateTime;

public record DashboardSummaryResponse(
        long totalRiskCount,
        long criticalRiskCount,
        long warningRiskCount,
        long normalRiskCount,
        double averageStockDays,
        long affectedMaterialCount,
        OffsetDateTime lastUpdatedAt
) {}

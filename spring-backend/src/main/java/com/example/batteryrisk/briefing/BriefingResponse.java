package com.example.batteryrisk.briefing;

import com.example.batteryrisk.common.EvidenceType;
import com.example.batteryrisk.common.ProcessingStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record BriefingResponse(
        long briefingId,
        long riskId,
        ProcessingStatus status,
        String headline,
        String eventSummary,
        Perspective inventoryPerspective,
        Perspective contractPerspective,
        List<String> recommendedActions,
        List<String> warnings,
        List<Reference> references,
        OffsetDateTime generatedAt
) {
    public record Perspective(String summary, EvidenceType evidenceType) {}
    public record Reference(String referenceType, Long contractId, Long clauseId, String label) {}
}

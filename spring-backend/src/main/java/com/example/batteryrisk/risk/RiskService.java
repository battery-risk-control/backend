package com.example.batteryrisk.risk;

import com.example.batteryrisk.common.EvidenceType;
import com.example.batteryrisk.common.ImpactDomain;
import com.example.batteryrisk.common.ResourceNotFoundException;
import com.example.batteryrisk.common.Severity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;

@Service
public class RiskService {

    private static final long VALID_RISK_ID = 101L;

    public PageResponse<RiskListItemResponse> getRisks(
            Severity severity,
            ImpactDomain impactDomain,
            Long materialId,
            Long supplierId,
            int page,
            int size
    ) {
        RiskListItemResponse risk = createDummyRisk(VALID_RISK_ID);

        /*
         * MVP Dummy 단계의 간단한 필터 처리입니다.
         * 필터가 더미 데이터와 일치하지 않으면 빈 목록을 반환합니다.
         */
        boolean matchesSeverity =
                severity == null || risk.severity() == severity;

        boolean matchesImpactDomain =
                impactDomain == null || risk.impactDomain() == impactDomain;

        boolean matchesMaterial =
                materialId == null || risk.materialId() == materialId;

        boolean matchesSupplier =
                supplierId == null || risk.supplierId() == supplierId;

        if (!matchesSeverity
                || !matchesImpactDomain
                || !matchesMaterial
                || !matchesSupplier) {

            return new PageResponse<>(
                    List.of(),
                    page,
                    size,
                    0,
                    0
            );
        }

        return new PageResponse<>(
                List.of(risk),
                page,
                size,
                1,
                1
        );
    }

    public RiskDetailResponse getRisk(long riskId) {
        if (riskId != VALID_RISK_ID) {
            throw new ResourceNotFoundException(
                    "RISK_NOT_FOUND",
                    "해당 리스크 정보를 찾을 수 없습니다. riskId=" + riskId
            );
        }
        OffsetDateTime detectedAt = OffsetDateTime.now();
        return new RiskDetailResponse(
                riskId,
                "칠레 리튬 생산 지역 폭우 발생",
                new RiskDetailResponse.Source(
                        "GDELT", "https://example.com/article/101", detectedAt.minusHours(1)),
                new RiskDetailResponse.Material(1L, "Lithium", true),
                new RiskDetailResponse.Supplier(11L, "SQM", "CL", false),
                new RiskDetailResponse.Analysis(
                        ImpactDomain.PRODUCTION, 0.91, Severity.CRITICAL, 87.3,
                        EvidenceType.CONFIRMED,
                        List.of("GDACS_RED_ALERT", "LOW_STOCK_COVERAGE", "NEGATIVE_NEWS_TONE")),
                new RiskDetailResponse.Inventory(
                        2500.0, "TON", 12, 20, LocalDate.of(2026, 8, 4)),
                detectedAt
        );
    }

    private RiskListItemResponse createDummyRisk(long riskId) {
        return new RiskListItemResponse(
                riskId,
                "칠레 리튬 생산 지역 폭우 발생",
                1L,
                "Lithium",
                11L,
                "SQM",
                "CL",
                ImpactDomain.PRODUCTION,
                Severity.CRITICAL,
                87.3,
                12,
                EvidenceType.CONFIRMED,
                OffsetDateTime.now()
        );
    }
}

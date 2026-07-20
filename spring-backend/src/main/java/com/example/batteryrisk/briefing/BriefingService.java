package com.example.batteryrisk.briefing;

import com.example.batteryrisk.common.EvidenceType;
import com.example.batteryrisk.common.ProcessingStatus;
import com.example.batteryrisk.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class BriefingService {

    private static final long VALID_RISK_ID = 101L;

    public BriefingResponse getBriefing(long riskId) {
        if (riskId != VALID_RISK_ID) {
            throw new ResourceNotFoundException(
                    "BRIEFING_NOT_FOUND",
                    "해당 리스크의 브리핑을 찾을 수 없습니다. riskId=" + riskId
            );
        }

        BriefingResponse.Perspective inventoryPerspective =
                new BriefingResponse.Perspective(
                        "현재 재고는 약 12일분이며 안전재고 기준 20일보다 낮다.",
                        EvidenceType.CONFIRMED
                );

        BriefingResponse.Perspective contractPerspective =
                new BriefingResponse.Perspective(
                        "계약상 가격 조정 임계치와 현재 가격 변동률을 비교 검토해야 한다.",
                        EvidenceType.CONFIRMED
                );

        BriefingResponse.Reference contractReference =
                new BriefingResponse.Reference(
                        "CONTRACT",
                        501L,
                        8001L,
                        "LTA-2026-001 가격 조정 조항"
                );

        BriefingResponse.Reference erpReference =
                new BriefingResponse.Reference(
                        "ERP",
                        null,
                        null,
                        "2026-07-20 기준 리튬 재고"
                );

        return new BriefingResponse(
                7001L,
                riskId,
                ProcessingStatus.COMPLETED,
                "칠레 리튬 공급 차질 관련 긴급 브리핑",
                "칠레 주요 리튬 생산 지역에서 폭우로 인한 생산 차질 신호가 감지되었다.",
                inventoryPerspective,
                contractPerspective,
                List.of(
                        "입고 예정일과 실제 ETA를 확인한다.",
                        "가격 조정 조항 적용 가능 여부를 검토한다.",
                        "인증된 대체 공급사의 가용 물량을 확인한다."
                ),
                List.of(),
                List.of(
                        contractReference,
                        erpReference
                ),
                OffsetDateTime.now()
        );
    }
}

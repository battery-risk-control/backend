package com.example.batteryrisk.contract;

import com.example.batteryrisk.common.ProcessingStatus;
import com.example.batteryrisk.common.ResourceNotFoundException;
import com.example.batteryrisk.risk.PageResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ContractService {

    private static final long VALID_CONTRACT_ID = 501L;

    public PageResponse<ContractListItemResponse> getContracts(
            Long supplierId,
            Long materialId,
            String status,
            int page,
            int size
    ) {
        ContractListItemResponse contract = createDummyContractListItem();

        boolean matchesSupplier =
                supplierId == null || contract.supplierId() == supplierId;

        boolean matchesMaterial =
                materialId == null || contract.materialId() == materialId;

        boolean matchesStatus =
                status == null
                        || contract.status().equalsIgnoreCase(status);

        if (!matchesSupplier || !matchesMaterial || !matchesStatus) {
            return new PageResponse<>(
                    List.of(),
                    page,
                    size,
                    0,
                    0
            );
        }

        return new PageResponse<>(
                List.of(contract),
                page,
                size,
                1,
                1
        );
    }

    public ContractDetailResponse getContract(long contractId) {
        if (contractId != VALID_CONTRACT_ID) {
            throw new ResourceNotFoundException(
                    "CONTRACT_NOT_FOUND",
                    "해당 계약 정보를 찾을 수 없습니다. contractId=" + contractId
            );
        }

        ContractDetailResponse.ClauseResponse priceEscalationClause =
                new ContractDetailResponse.ClauseResponse(
                        8001L,
                        "PRICE_ESCALATION",
                        "가격 조정 조항",
                        "기준 가격 대비 10% 이상 변동 시 가격 재협상이 가능하다.",
                        10.0,
                        "PERCENT"
                );

        ContractDetailResponse.ClauseResponse forceMajeureClause =
                new ContractDetailResponse.ClauseResponse(
                        8002L,
                        "FORCE_MAJEURE",
                        "불가항력 조항",
                        "천재지변 및 정부 수출 제한을 불가항력으로 인정한다.",
                        null,
                        null
                );

        return new ContractDetailResponse(
                contractId,
                "LTA-2026-001",
                11L,
                "SQM",
                1L,
                "Lithium",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2028, 12, 31),
                "USD",
                14000.0,
                "ACTIVE",
                ProcessingStatus.COMPLETED,
                List.of(
                        priceEscalationClause,
                        forceMajeureClause
                )
        );
    }

    private ContractListItemResponse createDummyContractListItem() {
        return new ContractListItemResponse(
                VALID_CONTRACT_ID,
                "LTA-2026-001",
                11L,
                "SQM",
                1L,
                "Lithium",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2028, 12, 31),
                "ACTIVE",
                true
        );
    }
}

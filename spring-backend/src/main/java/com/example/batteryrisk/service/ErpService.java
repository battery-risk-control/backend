package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.ErpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;

/** F1의 재고·사용량·발주·공급사 의존도를 결정적인 수식으로 계산합니다. */
@Service
public class ErpService {
    private static final int DIVISION_SCALE = 14;

    private final ErpRepository repository;

    public ErpService(ErpRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ErpDto.ContextResponse buildContext(ErpDto.ContextRequest request) {
        String erpMaterialId = request.erpMaterialId().trim();
        ErpRepository.MaterialRow material = repository.findMaterial(erpMaterialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ERP_MATERIAL_NOT_FOUND));

        ErpRepository.InventoryRow inventory = repository.aggregateCurrentInventory(
                material.materialId(), request.asOf());
        if (inventory.rowCount() == 0) {
            throw new BusinessException(
                    ErrorCode.ERP_CONTEXT_NOT_FOUND,
                    "분석 기준 시각에 사용할 수 있는 현재 재고가 없습니다.");
        }

        ErpRepository.ConsumptionRow consumption = repository.aggregateCurrentConsumption(
                material.materialId(), request.asOf());
        ErpRepository.SupplyRow supply = repository.findSupply(
                        material.materialId(), request.erpSupplierId(), request.asOf().toLocalDate())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERP_CONTEXT_NOT_FOUND,
                        "자재와 연결된 유효 공급사·계약을 찾을 수 없습니다."));

        BigDecimal availableQuantity = inventory.onHandQuantity()
                .subtract(inventory.reservedQuantity())
                .subtract(inventory.blockedQuantity())
                .subtract(inventory.qualityHoldQuantity());

        BigDecimal averageDailyUsage = positiveOrNull(consumption.averageDailyUsage());
        BigDecimal safetyStockShortageQuantity = inventory.safetyStockQuantity()
                .subtract(availableQuantity).max(BigDecimal.ZERO);
        BigDecimal inventoryDays = divideOrNull(availableQuantity, averageDailyUsage);
        BigDecimal safetyStockDays = divideOrNull(inventory.safetyStockQuantity(), averageDailyUsage);

        ErpRepository.InboundRow inbound = repository.findNextInbound(
                material.materialId(), request.asOf().toLocalDate()).orElse(null);
        Integer nextEtaDays = inbound == null ? null : Math.toIntExact(ChronoUnit.DAYS.between(
                request.asOf().toLocalDate(), inbound.effectiveArrivalDate()));
        BigDecimal expectedSupplyGapDays = inventoryDays == null || nextEtaDays == null
                ? null
                : BigDecimal.valueOf(nextEtaDays).subtract(inventoryDays).max(BigDecimal.ZERO);

        String alternativeStatus = repository.findAlternativeSupplierStatus(
                material.materialId(), supply.supplierId(), request.asOf().toLocalDate());
        String dataQualityStatus = dataQualityStatus(inventory, consumption, averageDailyUsage);

        return new ErpDto.ContextResponse(
                material.materialId(),
                material.erpMaterialId(),
                material.materialName(),
                material.unit(),
                normalize(inventory.onHandQuantity()),
                normalize(inventory.reservedQuantity()),
                normalize(inventory.blockedQuantity()),
                normalize(inventory.qualityHoldQuantity()),
                normalize(availableQuantity),
                normalize(averageDailyUsage),
                normalize(inventory.safetyStockQuantity()),
                normalize(safetyStockShortageQuantity),
                normalize(inventoryDays),
                normalize(safetyStockDays),
                inbound == null ? null : inbound.effectiveArrivalDate(),
                nextEtaDays,
                normalize(expectedSupplyGapDays),
                normalize(repository.sumRemainingQuantity(
                        material.materialId(), request.asOf().toLocalDate())),
                normalize(supply.dependencyRatio()),
                inbound == null ? "NONE" : inbound.orderStatus(),
                alternativeStatus,
                supply.supplierStatus(),
                supply.feocStatus(),
                supply.supplierId(),
                supply.erpSupplierId(),
                supply.contractId(),
                supply.erpContractId(),
                dataQualityStatus,
                request.asOf(),
                "ERP_MOCK",
                true
        );
    }

    private static String dataQualityStatus(
            ErpRepository.InventoryRow inventory,
            ErpRepository.ConsumptionRow consumption,
            BigDecimal averageDailyUsage) {
        if (consumption.rowCount() == 0 || consumption.missingUsageCount() > 0 || averageDailyUsage == null) {
            return "INCOMPLETE";
        }
        if (inventory.invalidCount() > 0 || consumption.invalidCount() > 0) {
            return "STALE";
        }
        return "VALID";
    }

    private static BigDecimal divideOrNull(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, DIVISION_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}

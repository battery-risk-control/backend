package com.example.batteryrisk;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.service.ErpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErpFeatureTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-07-22T00:00:00+09:00");

    private ErpRepository repository;
    private ErpService service;

    @BeforeEach
    void setUp() {
        repository = mock(ErpRepository.class);
        service = new ErpService(repository);
    }

    @Test
    void calculatesDeterministicLithiumContextFromErpValues() {
        when(repository.findMaterial("MAT-LI-CARB"))
                .thenReturn(Optional.of(new ErpRepository.MaterialRow(
                        1L, "MAT-LI-CARB", "Lithium Carbonate", "KG")));
        when(repository.aggregateCurrentInventory(1L, AS_OF))
                .thenReturn(new ErpRepository.InventoryRow(
                        bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("15000"), 4, 0));
        when(repository.aggregateCurrentConsumption(1L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(bd("1000"), 3, 0, 0));
        when(repository.findSupply(1L, null, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.SupplyRow(
                        11L, "SUP-CHL-01", "ACTIVE", "NO", 101L, "CTR-001", bd("0.45"))));
        when(repository.findNextInbound(1L, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.InboundRow(
                        LocalDate.parse("2026-07-30"), "CONFIRMED")));
        when(repository.findAlternativeSupplierStatus(1L, 11L, AS_OF.toLocalDate()))
                .thenReturn("APPROVED");
        when(repository.sumRemainingQuantity(1L, AS_OF.toLocalDate())).thenReturn(bd("134900"));

        ErpDto.ContextResponse response = service.buildContext(
                new ErpDto.ContextRequest(" MAT-LI-CARB ", null, AS_OF));

        assertThat(response.availableQuantity()).isEqualByComparingTo("36000");
        assertThat(response.averageDailyUsage()).isEqualByComparingTo("1000");
        assertThat(response.inventoryDays()).isEqualByComparingTo("36");
        assertThat(response.safetyStockDays()).isEqualByComparingTo("15");
        assertThat(response.safetyStockShortageQuantity()).isEqualByComparingTo("0");
        assertThat(response.nextInboundDate()).isEqualTo(LocalDate.parse("2026-07-30"));
        assertThat(response.nextEtaDays()).isEqualTo(8);
        assertThat(response.expectedSupplyGapDays()).isEqualByComparingTo("0");
        assertThat(response.supplierDependencyRatio()).isEqualByComparingTo("0.45");
        assertThat(response.remainingQuantity()).isEqualByComparingTo("134900");
        assertThat(response.alternativeSupplierStatus()).isEqualTo("APPROVED");
        assertThat(response.feocStatus()).isEqualTo("NO");
        assertThat(response.dataQualityStatus()).isEqualTo("VALID");
        assertThat(response.dataSource()).isEqualTo("ERP_MOCK");
        assertThat(response.mock()).isTrue();
    }

    @Test
    void keepsKnownEtaButDoesNotInventUsageBasedValuesWhenUsageIsMissing() {
        when(repository.findMaterial("MAT-MN-SULF"))
                .thenReturn(Optional.of(new ErpRepository.MaterialRow(
                        2L, "MAT-MN-SULF", "Manganese Sulfate", "KG")));
        when(repository.aggregateCurrentInventory(2L, AS_OF))
                .thenReturn(new ErpRepository.InventoryRow(
                        bd("1000"), bd("100"), bd("0"), bd("0"), bd("500"), 1, 0));
        when(repository.aggregateCurrentConsumption(2L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(null, 1, 1, 1));
        when(repository.findSupply(2L, null, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.SupplyRow(
                        12L, "SUP-ZAF-01", "ACTIVE", "NO", 102L, "CTR-010", bd("0.60"))));
        when(repository.findNextInbound(2L, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.InboundRow(
                        LocalDate.parse("2026-08-20"), "CONFIRMED")));
        when(repository.findAlternativeSupplierStatus(2L, 12L, AS_OF.toLocalDate()))
                .thenReturn("NONE");
        when(repository.sumRemainingQuantity(2L, AS_OF.toLocalDate())).thenReturn(bd("9000"));

        ErpDto.ContextResponse response = service.buildContext(
                new ErpDto.ContextRequest("MAT-MN-SULF", null, AS_OF));

        assertThat(response.availableQuantity()).isEqualByComparingTo("900");
        assertThat(response.averageDailyUsage()).isNull();
        assertThat(response.inventoryDays()).isNull();
        assertThat(response.safetyStockDays()).isNull();
        assertThat(response.safetyStockShortageQuantity()).isEqualByComparingTo("0");
        assertThat(response.nextInboundDate()).isEqualTo(LocalDate.parse("2026-08-20"));
        assertThat(response.nextEtaDays()).isEqualTo(29);
        assertThat(response.expectedSupplyGapDays()).isNull();
        assertThat(response.dataQualityStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    void rejectsUnknownExternalMaterialId() {
        when(repository.findMaterial("MAT-UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildContext(
                new ErpDto.ContextRequest("MAT-UNKNOWN", null, AS_OF)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ERP_MATERIAL_NOT_FOUND));
    }

    @Test
    void rejectsMaterialWithoutCurrentInventory() {
        when(repository.findMaterial("MAT-LI-CARB"))
                .thenReturn(Optional.of(new ErpRepository.MaterialRow(
                        1L, "MAT-LI-CARB", "Lithium Carbonate", "KG")));
        when(repository.aggregateCurrentInventory(eq(1L), any(OffsetDateTime.class)))
                .thenReturn(new ErpRepository.InventoryRow(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, 0, 0));

        assertThatThrownBy(() -> service.buildContext(
                new ErpDto.ContextRequest("MAT-LI-CARB", null, AS_OF)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ERP_CONTEXT_NOT_FOUND));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

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
                        bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("15000"), 4, 0, 0));
        when(repository.aggregateCurrentConsumption(1L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(bd("1000"), 3, 0, 0, 0));
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
                        bd("1000"), bd("100"), bd("0"), bd("0"), bd("500"), 1, 0, 0));
        // MISSING_USAGE 행은 STALE도 INVALID도 아니다 — 결측은 missingUsageCount가 잡는다.
        when(repository.aggregateCurrentConsumption(2L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(null, 1, 1, 0, 0));
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

    /**
     * INVALID 행은 INVALID로 나가야 한다 — STALE로 뭉개면 안 된다.
     *
     * <p>두 상태는 뒤에서 전혀 다르게 다뤄진다. FastAPI Severity 엔진은 STALE이면 점수를 내고
     * 사유 코드만 붙이지만(STALE_DATA_QUALITY), INVALID면 채점 자체를 거부한다
     * (INVALID_DATA_QUALITY, severity UNKNOWN). 뭉개면 "쓸 수 없다"고 표시된 데이터로 계산한
     * 정상 점수가 화면에 올라가고, 사용자에게는 "오래된 스냅샷"이라고만 보인다.
     */
    @Test
    void reportsInvalidDataAsInvalidNotStale() {
        givenMaterialWithQualityCounts(
                /* inventoryStale */ 0, /* inventoryInvalid */ 1,
                /* consumptionStale */ 0, /* consumptionInvalid */ 0);

        ErpDto.ContextResponse response = service.buildContext(
                new ErpDto.ContextRequest("MAT-LI-CARB", null, AS_OF));

        assertThat(response.dataQualityStatus()).isEqualTo("INVALID");
    }

    /** 소비 이력 쪽 INVALID도 같다 — 어느 표에서 왔든 "쓸 수 없다"는 사실은 같다. */
    @Test
    void reportsInvalidFromConsumptionRowsToo() {
        givenMaterialWithQualityCounts(0, 0, 0, 1);

        assertThat(service.buildContext(new ErpDto.ContextRequest("MAT-LI-CARB", null, AS_OF))
                .dataQualityStatus()).isEqualTo("INVALID");
    }

    /** STALE 행만 있으면 그대로 STALE. INVALID 분리 때문에 이쪽이 바뀌면 안 된다. */
    @Test
    void reportsStaleWhenOnlyStaleRowsExist() {
        givenMaterialWithQualityCounts(2, 0, 0, 0);

        assertThat(service.buildContext(new ErpDto.ContextRequest("MAT-LI-CARB", null, AS_OF))
                .dataQualityStatus()).isEqualTo("STALE");
    }

    /**
     * 결측과 INVALID가 겹치면 INVALID가 이긴다 — DATA_QUALITY_RANK의 순서
     * (VALID &lt; STALE &lt; INCOMPLETE &lt; INVALID)를 따른다.
     */
    @Test
    void invalidBeatsIncompleteWhenBothApply() {
        when(repository.findMaterial("MAT-LI-CARB"))
                .thenReturn(Optional.of(new ErpRepository.MaterialRow(
                        1L, "MAT-LI-CARB", "Lithium Carbonate", "KG")));
        when(repository.aggregateCurrentInventory(1L, AS_OF))
                .thenReturn(new ErpRepository.InventoryRow(
                        bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("15000"), 4, 0, 1));
        // 사용량이 결측이라 INCOMPLETE 조건도 함께 성립한다.
        when(repository.aggregateCurrentConsumption(1L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(null, 1, 1, 0, 0));
        givenSupplyAndInbound();

        assertThat(service.buildContext(new ErpDto.ContextRequest("MAT-LI-CARB", null, AS_OF))
                .dataQualityStatus()).isEqualTo("INVALID");
    }

    /** 품질 카운터만 바꿔 가며 판정을 보는 준비 helper. 나머지 값은 VALID 케이스와 같다. */
    private void givenMaterialWithQualityCounts(
            int inventoryStale, int inventoryInvalid, int consumptionStale, int consumptionInvalid) {
        when(repository.findMaterial("MAT-LI-CARB"))
                .thenReturn(Optional.of(new ErpRepository.MaterialRow(
                        1L, "MAT-LI-CARB", "Lithium Carbonate", "KG")));
        when(repository.aggregateCurrentInventory(1L, AS_OF))
                .thenReturn(new ErpRepository.InventoryRow(
                        bd("40320"), bd("2376"), bd("1080"), bd("864"), bd("15000"),
                        4, inventoryStale, inventoryInvalid));
        when(repository.aggregateCurrentConsumption(1L, AS_OF))
                .thenReturn(new ErpRepository.ConsumptionRow(
                        bd("1000"), 3, 0, consumptionStale, consumptionInvalid));
        givenSupplyAndInbound();
    }

    private void givenSupplyAndInbound() {
        when(repository.findSupply(1L, null, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.SupplyRow(
                        11L, "SUP-CHL-01", "ACTIVE", "NO", 101L, "CTR-001", bd("0.45"))));
        when(repository.findNextInbound(1L, AS_OF.toLocalDate()))
                .thenReturn(Optional.of(new ErpRepository.InboundRow(
                        LocalDate.parse("2026-07-30"), "CONFIRMED")));
        when(repository.findAlternativeSupplierStatus(1L, 11L, AS_OF.toLocalDate()))
                .thenReturn("APPROVED");
        when(repository.sumRemainingQuantity(1L, AS_OF.toLocalDate())).thenReturn(bd("134900"));
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
                        BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0));

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

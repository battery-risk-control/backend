package com.example.batteryrisk;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.DashboardRepository;
import com.example.batteryrisk.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 14단계 조회·집계 서비스 단위 테스트.
 *
 * <p>Repository의 PostgreSQL 전용 SQL(DISTINCT ON 등)은 H2에서 재현되지 않아
 * (그 부분은 실제 PostgreSQL로 검증) 여기서는 서비스 계층의 검증·정규화·집계 로직만 다룬다.
 */
class DashboardServiceTest {

    private DashboardRepository repository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        repository = mock(DashboardRepository.class);
        service = new DashboardService(repository);
    }

    @Test
    void summaryReturnsRepositoryResultAsIs() {
        DashboardDto.Summary summary = new DashboardDto.Summary(
                2, 1, 1, 0, 0, 3, 11, 19, 29, 4,
                OffsetDateTime.parse("2026-07-25T10:00:00+09:00"), true);
        when(repository.loadSummary()).thenReturn(summary);

        assertThat(service.summary()).isSameAs(summary);
    }

    @Test
    void materialRisksNormalizesLowercaseSeverityBeforeQuery() {
        when(repository.findMaterialRisks("CRITICAL", 5)).thenReturn(List.of());

        service.materialRisks("  critical ", 5);

        verify(repository).findMaterialRisks("CRITICAL", 5);
    }

    @Test
    void materialRisksTreatsBlankSeverityAsNoFilter() {
        when(repository.findMaterialRisks(null, 20)).thenReturn(List.of());

        service.materialRisks("   ", 20);

        verify(repository).findMaterialRisks(isNull(), eq(20));
    }

    @Test
    void materialRisksRejectsUnknownSeverityWithoutHittingRepository() {
        assertThatThrownBy(() -> service.materialRisks("HIGH", 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(repository);
    }

    @Test
    void importDependencySumsShareRatioAndSkipsNullShares() {
        when(repository.findMaterialName("MAT-LI-CARB")).thenReturn("Lithium Carbonate");
        when(repository.findImportDependency(eq("MAT-LI-CARB"), org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(List.of(
                        item("SUP-CHL-01", new BigDecimal("0.45")),
                        item("SUP-AUS-01", new BigDecimal("0.30")),
                        item("SUP-NULL-01", null)));

        DashboardDto.ImportDependency result = service.importDependency("MAT-LI-CARB", null);

        assertThat(result.erpMaterialId()).isEqualTo("MAT-LI-CARB");
        assertThat(result.materialName()).isEqualTo("Lithium Carbonate");
        assertThat(result.totalShareRatio()).isEqualByComparingTo("0.75");
        assertThat(result.breakdown()).hasSize(3);
    }

    @Test
    void importDependencyUsesProvidedAsOfDate() {
        OffsetDateTime asOf = OffsetDateTime.parse("2026-06-01T12:00:00+09:00");
        when(repository.findMaterialName("MAT-LI-CARB")).thenReturn("Lithium Carbonate");
        when(repository.findImportDependency(eq("MAT-LI-CARB"), eq(LocalDate.parse("2026-06-01"))))
                .thenReturn(List.of());

        service.importDependency("MAT-LI-CARB", asOf);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).findImportDependency(eq("MAT-LI-CARB"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    void importDependencyRejectsUnknownMaterial() {
        when(repository.findMaterialName("MAT-NOPE")).thenReturn(null);

        assertThatThrownBy(() -> service.importDependency("MAT-NOPE", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ERP_MATERIAL_NOT_FOUND));

        verify(repository, never()).findImportDependency(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void contractsWrapsContentWithTotalCountAndComputedPages() {
        DashboardDto.ContractItem contract = new DashboardDto.ContractItem(
                2L, "CTR-001", "BA-2025-0001", "Lithium Carbonate Supply Agreement 1",
                "SUP-CHL-01", "Atacama Lithium Partners", "MAT-LI-CARB", "Lithium Carbonate",
                "ACTIVE", "PRIMARY", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        when(repository.findContractPage("ACTIVE", 0, 3)).thenReturn(List.of(contract));
        when(repository.countContracts("ACTIVE")).thenReturn(7L);

        PageResponse<DashboardDto.ContractItem> page = service.contracts("active", 0, 3);

        assertThat(page.content()).containsExactly(contract);
        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(7L);
        assertThat(page.totalPages()).isEqualTo(3); // ceil(7 / 3)
        verify(repository).findContractPage("ACTIVE", 0, 3);
        verify(repository).countContracts("ACTIVE");
    }

    private static DashboardDto.ImportDependencyItem item(String erpSupplierId, BigDecimal share) {
        return new DashboardDto.ImportDependencyItem(
                erpSupplierId, erpSupplierId + " name", "CL", share, "APPROVED", false);
    }
}

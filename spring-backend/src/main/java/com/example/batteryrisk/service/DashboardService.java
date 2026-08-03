package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.DashboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 14단계 조회·집계 API. 저장은 하지 않고 PostgreSQL 집계 결과만 화면 형태로 반환한다. */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    /** "완료 처리 항목" 기본 노출 건수. 되돌릴 대상을 찾는 용도라 최신 몇 건이면 충분하다. */
    private static final int DEFAULT_ACKNOWLEDGED_LIMIT = 5;

    private static final Set<String> LEVELS = Set.of("NORMAL", "WARNING", "CRITICAL", "UNKNOWN");

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    public DashboardDto.Summary summary() {
        return repository.loadSummary();
    }

    public DashboardDto.ProcurementRiskSummary procurementRiskSummary() {
        return repository.loadProcurementRiskSummary();
    }

    /**
     * 원자재별 리스크 요약 7종. 요약 행을 먼저 만들고 평가가 있는 자재에만 "주요 이슈" 3건을
     * 채운다 — 평가가 0건인 자재까지 조회하면 7번 중 대부분이 빈 결과를 받는 낭비다.
     */
    public List<DashboardDto.MaterialRiskSummaryItem> materialRiskSummary() {
        return repository.findMaterialRiskSummary().stream()
                .map(item -> item.riskScore() == null
                        ? item
                        : new DashboardDto.MaterialRiskSummaryItem(
                                item.materialCategory(), item.materialName(), item.riskScore(),
                                item.riskLevel(), item.riskScore24hAgo(), item.scoreDelta(),
                                item.latestAssessmentId(),
                                repository.findMaterialRiskTopNews(item.materialCategory())))
                .toList();
    }

    /**
     * 완료 처리된 평가 목록. 화면의 "완료 처리 항목"이 되돌리기 버튼을 놓는 자리다.
     *
     * <p>상한을 두는 이유: 이 목록은 되돌릴 대상을 찾는 곳이지 완료 이력 전체를 보는 곳이
     * 아니다. 오래된 처리를 되돌릴 일은 거의 없고, 있어도 최신순으로 몇 건 안에 들어온다.
     */
    public List<DashboardDto.AcknowledgedItem> acknowledgedAssessments(Integer limit) {
        int size = limit == null || limit < 1 ? DEFAULT_ACKNOWLEDGED_LIMIT : Math.min(limit, 50);
        return repository.findAcknowledged(size);
    }

    public List<DashboardDto.MaterialRiskItem> materialRisks(String severity, int limit) {
        String normalized = normalize(severity);
        if (normalized != null && !LEVELS.contains(normalized)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "severity는 NORMAL/WARNING/CRITICAL/UNKNOWN 중 하나여야 합니다.");
        }
        return repository.findMaterialRisks(normalized, limit);
    }

    public DashboardDto.ImportDependency importDependency(String erpMaterialId, OffsetDateTime asOf) {
        String materialName = repository.findMaterialName(erpMaterialId);
        if (materialName == null) {
            throw new BusinessException(ErrorCode.ERP_MATERIAL_NOT_FOUND);
        }
        LocalDate asOfDate = (asOf == null ? OffsetDateTime.now() : asOf).toLocalDate();
        List<DashboardDto.ImportDependencyItem> breakdown =
                repository.findImportDependency(erpMaterialId, asOfDate);
        BigDecimal total = breakdown.stream()
                .map(DashboardDto.ImportDependencyItem::supplyShareRatio)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DashboardDto.ImportDependency(erpMaterialId, materialName, total, breakdown);
    }

    public PageResponse<DashboardDto.ContractItem> contracts(String status, int page, int size) {
        String normalized = normalize(status);
        List<DashboardDto.ContractItem> content = repository.findContractPage(normalized, page, size);
        return PageResponse.of(content, page, size, repository.countContracts(normalized));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

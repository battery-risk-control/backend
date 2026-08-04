package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.DashboardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** 14단계 조회·집계 API. 저장은 하지 않고 PostgreSQL 집계 결과만 화면 형태로 반환한다. */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** "완료 처리 항목" 기본 노출 건수. 되돌릴 대상을 찾는 용도라 최신 몇 건이면 충분하다. */
    private static final int DEFAULT_ACKNOWLEDGED_LIMIT = 5;

    private static final Set<String> LEVELS = Set.of("NORMAL", "WARNING", "CRITICAL", "UNKNOWN");

    private final DashboardRepository repository;

    /**
     * 마지막으로 로그에 남긴 orphan 건수. 요청마다 같은 줄을 찍지 않기 위한 것이다 —
     * KPI는 대시보드를 열 때마다 조회되므로 조건 없이 찍으면 로그가 이 한 줄로 덮인다.
     * -1은 "아직 한 번도 안 찍었다"라 0건이어도 최초 1회는 남는다.
     */
    private final AtomicLong lastLoggedOrphanCount = new AtomicLong(-1);

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    public DashboardDto.Summary summary() {
        return repository.loadSummary();
    }

    public DashboardDto.ProcurementRiskSummary procurementRiskSummary() {
        logOrphanNewsBriefings();
        return repository.loadProcurementRiskSummary();
    }

    /**
     * 수집 이벤트에 연결되지 않아 운영 집계에서 빠진 완결 NEWS 브리핑을 알린다.
     *
     * <p>이 브리핑들은 지도·주요 알림·KPI·리스크 모니터링 목록 어디에도 나오지 않는다
     * ({@code NewsEventSql} 참고). 화면에서 사라지는 것 자체는 의도한 동작이지만 <b>말없이</b>
     * 사라지면 안 된다 — 시연 시드나 수동 {@code /analyze} 호출이 쌓이고 있다는 신호이고,
     * 실제로 "지도에는 리튬 주의가 뜨는데 알림에는 없다"는 혼선의 원인이었다.
     *
     * <p>건수가 바뀔 때만 남긴다. 집계 자체는 실패해도 대시보드를 막지 않는다 — 품질 점검이
     * 본 기능을 쓰러뜨리면 안 된다.
     */
    private void logOrphanNewsBriefings() {
        try {
            long orphans = repository.countOrphanNewsBriefings();
            if (lastLoggedOrphanCount.getAndSet(orphans) == orphans) {
                return;
            }
            if (orphans > 0) {
                log.warn("수집 이벤트(raw_events)에 연결되지 않은 완결 NEWS 브리핑 {}건이 운영 집계에서 "
                        + "제외됐습니다. 시연용 시드라면 정리하거나 별도 구분값을 두세요.", orphans);
            } else {
                log.info("수집 이벤트에 연결되지 않은 완결 NEWS 브리핑이 없습니다.");
            }
        } catch (RuntimeException exception) {
            log.warn("orphan NEWS 브리핑 집계에 실패했습니다. 대시보드 조회는 계속합니다.", exception);
        }
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

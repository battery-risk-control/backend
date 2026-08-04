package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.dto.SupplierDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.PlanningDashboardRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 2계층(경영기획팀, 코드상 Role.STRATEGY) 대시보드 7탭 조회·집계 API. 저장은 하지 않고
 * PostgreSQL 집계 결과(+FastAPI RAG 헬스체크 1건)만 화면 형태로 반환한다.
 *
 * <p>사업부(business_unit) 개념은 V27 마이그레이션(자재 대분류 8종 → 배터리셀사업부/
 * 소재부품사업부 5:3 분류)으로 처음 도입됐다 — 그 전까지는 프론트엔드만 이 개념을
 * 임시로 하드코딩해 썼다.
 */
@Service
@Transactional(readOnly = true)
public class PlanningDashboardService {
    private static final Logger log = LoggerFactory.getLogger(PlanningDashboardService.class);

    private static final int CONTRACT_EXPIRING_WITHIN_DAYS = 30;
    private static final int RECENT_BRIEFING_LIMIT = 10;
    private static final int VENDOR_HISTORY_LIMIT = 20;
    private static final DateTimeFormatter LAST_RUN_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** MATERIAL_NAME_KO(RiskEventService/PlanningDashboardRepository)와 동일한 8종. */
    private static final List<String> MATERIAL_CATEGORIES = List.of(
            "LITHIUM", "COBALT", "NICKEL", "GRAPHITE", "MANGANESE", "COPPER", "ALUMINUM", "RARE_EARTH");

    private final PlanningDashboardRepository repository;
    private final SupplierQualificationService supplierQualificationService;
    private final RestClient fastApiRestClient;

    public PlanningDashboardService(
            PlanningDashboardRepository repository,
            SupplierQualificationService supplierQualificationService,
            RestClient fastApiRestClient) {
        this.repository = repository;
        this.supplierQualificationService = supplierQualificationService;
        this.fastApiRestClient = fastApiRestClient;
    }

    // ===== 전략 대시보드 =====

    public PlanningDashboardDto.StrategyDashboard strategyDashboard() {
        List<PlanningDashboardDto.RiskExposureByUnit> exposureByUnit = repository.loadRiskExposureByUnit();
        List<PlanningDashboardDto.VendorRiskHistoryItem> vendorHistory =
                repository.loadVendorRiskHistory(VENDOR_HISTORY_LIMIT);
        PlanningDashboardRepository.QuarterKpi quarter = repository.quarterKpi();

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem(
                        "탐지된 리스크", BigDecimal.valueOf(quarter.detectedCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "심각 등급 비중", nullSafe(quarter.criticalRatio()), "%"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "평균 대응 소요", nullSafe(quarter.avgResponseDays()), "일"));

        return new PlanningDashboardDto.StrategyDashboard(
                "전체", currentQuarterLabel(), kpiSummary, exposureByUnit, vendorHistory);
    }

    // ===== 자재 위험 =====

    public PlanningDashboardDto.MaterialRiskDashboard materialRisk() {
        List<PlanningDashboardDto.MaterialRiskRankItem> ranking = repository.findMaterialRiskRanking();
        List<PlanningDashboardDto.RiskExposureByUnit> topUnitExposure = repository.loadTopMaterialUnitExposure();
        PlanningDashboardRepository.MaterialRiskKpi kpi = repository.loadMaterialRiskKpi();
        BigDecimal[] comparison = repository.loadQuarterScoreComparison();

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem("평가 자재", BigDecimal.valueOf(kpi.assessedCount()), "종"),
                new PlanningDashboardDto.KpiSummaryItem("심각 자재", BigDecimal.valueOf(kpi.criticalCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem("평균 재고일수", nullSafe(kpi.avgSafetyStockDays()), "일"),
                new PlanningDashboardDto.KpiSummaryItem("최고 위험 점수", nullSafe(kpi.maxScore()), "점"));

        return new PlanningDashboardDto.MaterialRiskDashboard(
                kpiSummary, ranking, topUnitExposure, quarterChangeLabel(comparison[0], comparison[1]));
    }

    private static String quarterChangeLabel(BigDecimal current, BigDecimal previous) {
        if (previous == null || current == null) {
            return "지난 분기 비교 데이터 없음";
        }
        int cmp = current.compareTo(previous);
        if (cmp > 0) {
            return "지난 분기 대비 위험 점수 상승";
        }
        if (cmp < 0) {
            return "지난 분기 대비 위험 점수 하락";
        }
        return "지난 분기와 동일";
    }

    // ===== 수입 의존도 =====

    public PlanningDashboardDto.ImportDependencyDashboard importDependency() {
        List<PlanningDashboardDto.CountryDependencyItem> byCountry = repository.loadImportDependencyByCountry();
        List<PlanningDashboardDto.UnitDependencyItem> byUnit = repository.loadImportDependencyByUnit();
        BigDecimal diversificationRate = repository.loadDiversificationRate();

        BigDecimal totalDependency = byCountry.stream()
                .map(PlanningDashboardDto.CountryDependencyItem::shareRatio)
                .filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        long overConcentratedCount = byCountry.stream()
                .filter(item -> item.shareRatio() != null && item.shareRatio().compareTo(BigDecimal.valueOf(80)) >= 0)
                .count();

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem("전체 수입 의존도", totalDependency, "%"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "단일국가 과의존", BigDecimal.valueOf(overConcentratedCount), "건"),
                new PlanningDashboardDto.KpiSummaryItem("다변화 진행률", nullSafe(diversificationRate), "%"));

        return new PlanningDashboardDto.ImportDependencyDashboard(
                kpiSummary, byCountry, byUnit, findAlternativeSuppliers());
    }

    /**
     * F9 적격 공급사 조회(SupplierQualificationService)를 자재 대분류 8종 전체에 돌려
     * is_alternative=true인 후보만 모은다. 카테고리별 1개 쿼리라 N=8 호출이지만, 이
     * 탭 자체가 자주 열리는 화면이 아니고 데이터 규모도 작아 캐싱 없이 그대로 둔다.
     */
    private List<PlanningDashboardDto.EntityBadgeItem> findAlternativeSuppliers() {
        List<PlanningDashboardDto.EntityBadgeItem> result = new java.util.ArrayList<>();
        for (String category : MATERIAL_CATEGORIES) {
            SupplierDto.QualifiedSuppliersResponse response =
                    supplierQualificationService.findQualifiedSuppliers(category);
            for (SupplierDto.SupplierCandidate candidate : response.candidates()) {
                if (!candidate.isAlternative()) {
                    continue;
                }
                result.add(new PlanningDashboardDto.EntityBadgeItem(
                        String.valueOf(candidate.supplierId()),
                        candidate.supplierName(),
                        "전환 시 의존도 개선 추정",
                        new PlanningDashboardDto.Badge("APPROVED", "success")));
                if (result.size() >= 5) {
                    return result;
                }
            }
        }
        return result;
    }

    // ===== 공급사 분석 =====

    public PlanningDashboardDto.SupplierAnalysisDashboard supplierAnalysis() {
        List<PlanningDashboardDto.SupplierRiskRankItem> ranking = repository.findSupplierRanking();
        PlanningDashboardRepository.AlternativeReviewProgress review = repository.loadAlternativeReviewProgress();

        long reviewCount = ranking.stream().filter(r -> "REVIEW".equals(r.approvedStatus())).count();
        long eventSum = ranking.stream().mapToLong(PlanningDashboardDto.SupplierRiskRankItem::riskCount90d).sum();

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem("전체 공급사", BigDecimal.valueOf(ranking.size()), "곳"),
                new PlanningDashboardDto.KpiSummaryItem("REVIEW 상태", BigDecimal.valueOf(reviewCount), "곳"),
                new PlanningDashboardDto.KpiSummaryItem("90일 이벤트", BigDecimal.valueOf(eventSum), "건"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "대체 검토 진행", BigDecimal.valueOf(review.pending()), "/" + review.total()));

        List<PlanningDashboardDto.EntityBadgeItem> recommended = ranking.stream()
                .filter(r -> "APPROVED".equals(r.approvedStatus()))
                .limit(3)
                .map(r -> new PlanningDashboardDto.EntityBadgeItem(
                        r.vendorId(), r.vendorName(), "최근 90일 이력 " + r.riskCount90d() + "건",
                        new PlanningDashboardDto.Badge("APPROVED", "success")))
                .toList();

        return new PlanningDashboardDto.SupplierAnalysisDashboard(kpiSummary, ranking, recommended);
    }

    // ===== 계약 현황 =====

    public PlanningDashboardDto.ContractStatusDashboard contractStatus() {
        PlanningDashboardRepository.ContractKpi kpi = repository.loadContractKpi(CONTRACT_EXPIRING_WITHIN_DAYS);
        List<PlanningDashboardDto.ContractCoverageItem> coverageByUnit = repository.loadContractCoverageByUnit();
        List<PlanningDashboardDto.EntityBadgeItem> expiring =
                repository.findExpiringContracts(CONTRACT_EXPIRING_WITHIN_DAYS, 10);

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem("ACTIVE", BigDecimal.valueOf(kpi.activeCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem("만료 임박", BigDecimal.valueOf(kpi.expiringCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem("문서 적재", BigDecimal.valueOf(kpi.documentCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "RAG 검색 가능", BigDecimal.valueOf(kpi.ragReadyCount()), "건"));

        return new PlanningDashboardDto.ContractStatusDashboard(kpiSummary, coverageByUnit, expiring);
    }

    // ===== AI 브리핑 =====

    public PlanningDashboardDto.AiBriefingSummaryDashboard aiBriefing() {
        List<PlanningDashboardDto.RankedBarItem> byUnit = repository.loadBriefingCountByUnit();
        List<PlanningDashboardDto.BriefingSummaryItem> recent = repository.findRecentBriefings(RECENT_BRIEFING_LIMIT);
        PlanningDashboardRepository.QuarterKpi quarter = repository.quarterKpi();
        long executiveFlagged = repository.loadExecutiveFlaggedCount();

        List<PlanningDashboardDto.KpiSummaryItem> kpiSummary = List.of(
                new PlanningDashboardDto.KpiSummaryItem(
                        "이번 분기 브리핑", BigDecimal.valueOf(quarter.detectedCount()), "건"),
                new PlanningDashboardDto.KpiSummaryItem("CRITICAL 비중", nullSafe(quarter.criticalRatio()), "%"),
                new PlanningDashboardDto.KpiSummaryItem("평균 대응 소요", nullSafe(quarter.avgResponseDays()), "일"),
                new PlanningDashboardDto.KpiSummaryItem(
                        "임원 보고 지정", BigDecimal.valueOf(executiveFlagged), "건"));

        return new PlanningDashboardDto.AiBriefingSummaryDashboard(kpiSummary, byUnit, recent);
    }

    // ===== 데이터 품질 =====

    public PlanningDashboardDto.DataQualityStatus dataQuality() {
        List<PlanningDashboardRepository.ConfidenceRawRow> rows = repository.loadConfidenceRawRows();
        List<PlanningDashboardDto.ConfidenceDistributionItem> distribution = confidenceDistribution(rows);

        long coverageCount = repository.loadMaterialCategoryCoverageCount();
        int coverageTotal = PlanningDashboardRepository.materialCategoryTotal();

        PlanningDashboardRepository.PipelineHealth erpHealth = repository.loadPipelineHealth("erp_seed");
        String erpSyncStatus = erpHealth == null ? "적재 이력 없음" : ("OK".equals(erpHealth.status()) ? "정상" : "실패");
        String lastUpdatedLabel = erpHealth == null
                ? "마지막 ERP 적재 이력 없음"
                : "마지막 ERP 적재: " + erpHealth.lastRunAt().atZoneSameInstant(ZoneOffset.ofHours(9))
                        .format(LAST_RUN_FORMATTER);

        return new PlanningDashboardDto.DataQualityStatus(
                erpSyncStatus, ragIndexStatus(), coverageCount, coverageTotal, lastUpdatedLabel, distribution);
    }

    private static List<PlanningDashboardDto.ConfidenceDistributionItem> confidenceDistribution(
            List<PlanningDashboardRepository.ConfidenceRawRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        long confirmed = 0;
        long reference = 0;
        long warning = 0;
        for (PlanningDashboardRepository.ConfidenceRawRow row : rows) {
            if (row.mock()) {
                reference++;
            } else if (row.confidence() == null || row.confidence() < 0.7) {
                warning++;
            } else {
                confirmed++;
            }
        }
        BigDecimal total = BigDecimal.valueOf(rows.size());
        return List.of(
                new PlanningDashboardDto.ConfidenceDistributionItem("확정", ratio(confirmed, total)),
                new PlanningDashboardDto.ConfidenceDistributionItem("참고", ratio(reference, total)),
                new PlanningDashboardDto.ConfidenceDistributionItem("경고", ratio(warning, total)));
    }

    private static BigDecimal ratio(long count, BigDecimal total) {
        return BigDecimal.valueOf(count).divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * FastAPI {@code GET /health}의 {@code vector_store.chunk_count}를 3단계 상태로
     * 매핑한다. FastAPI/RAG 코드는 전혀 건드리지 않고 이미 있는 헬스체크를 재사용한다.
     * 실패해도 예외를 밖으로 던지지 않는다(MarketPriceService와 동일한 방어적 호출 관례).
     */
    private String ragIndexStatus() {
        try {
            FastApiHealthResponse response = fastApiRestClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(FastApiHealthResponse.class);
            Integer chunkCount = response != null && response.vectorStore() != null
                    ? response.vectorStore().chunkCount() : null;
            return (chunkCount != null && chunkCount > 0) ? "정상" : "점검 필요";
        } catch (RestClientException e) {
            log.warn("FastAPI RAG 헬스체크 호출 실패: {}", e.getMessage());
            return "연결 실패";
        }
    }

    /**
     * {@code fastApiRestClient}(FastApiConfig)는 전역 SNAKE_CASE Jackson 네이밍 전략을 안 타는
     * {@code RestClient.builder()} 직접 생성 빈이라({@code KgResolverService}의
     * {@code kgServiceRestClient}와 동일한 이유), snake_case 필드는 명시적으로 매핑해야 한다.
     */
    private record FastApiHealthResponse(@JsonProperty("vector_store") VectorStoreHealth vectorStore) {}

    private record VectorStoreHealth(String status, @JsonProperty("chunk_count") Integer chunkCount) {}

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String currentQuarterLabel() {
        java.time.LocalDate today = java.time.LocalDate.now();
        int quarter = (today.getMonthValue() - 1) / 3 + 1;
        return today.getYear() + "Q" + quarter;
    }

    // ===== 드릴다운 상세 =====

    public PlanningDashboardDto.AiBriefingDetail aiBriefingDetail(String analysisId) {
        PlanningDashboardDto.AiBriefingDetail detail = repository.findBriefingDetail(analysisId);
        if (detail == null) {
            throw new BusinessException(ErrorCode.ANALYSIS_BRIEFING_NOT_FOUND);
        }
        return detail;
    }

    public PlanningDashboardDto.ContractDetail contractDetail(String contractNumber) {
        PlanningDashboardDto.ContractDetail detail = repository.findContractDetail(contractNumber);
        if (detail == null) {
            throw new BusinessException(ErrorCode.CONTRACT_NOT_FOUND);
        }
        return detail;
    }
}

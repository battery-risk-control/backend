package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2계층(경영기획팀, 코드상 Role.STRATEGY) 대시보드 7탭 조회·집계 전용 Repository.
 * {@link DashboardRepository}와 동일한 관례(NamedParameterJdbcTemplate + 화면이 바로
 * 쓸 수 있는 형태로 집계)를 따른다.
 *
 * <p>사업부(business_unit) 조인은 V27 마이그레이션의 {@code materials.business_unit_id}
 * (자재 단위 조인용)와 {@code material_category_business_units} 뷰(material_category만
 * 있는 procurement_risk_assessments/analyses용, material_id가 NULL일 수 있어 뷰로 우회)
 * 둘을 상황에 맞게 쓴다.
 */
@Repository
public class PlanningDashboardRepository {

    /**
     * 자재 대분류 8종 → 화면 표기명. {@link com.example.batteryrisk.service.RiskEventService}의
     * {@code MATERIAL_NAME_KO}와 값이 같아야 하지만 그쪽은 package-private이라 이 클래스에서
     * 직접 참조할 수 없다 — 5줄짜리 고정 맵이라 결합도보다 독립성을 우선해 중복 정의한다
     * (같은 이유로 confidenceLabel 규칙도 아래에서 중복 정의).
     */
    private static final Map<String, String> MATERIAL_NAME_KO = Map.of(
            "LITHIUM", "리튬",
            "COBALT", "코발트",
            "NICKEL", "니켈",
            "GRAPHITE", "흑연",
            "MANGANESE", "망간",
            "COPPER", "구리",
            "ALUMINUM", "알루미늄",
            "RARE_EARTH", "희토류");

    /**
     * analyses.material_category는 NULL 허용인데 {@code Map.of} 기반 불변 맵은 null 키
     * 조회 자체가 NPE다 — 실데이터(자재 미상 분석)에서 브리핑 목록·상세가 통째로 500이
     * 나던 원인. 조회 전에 거른다.
     */
    private static String materialNameKo(String category) {
        return category == null ? null : MATERIAL_NAME_KO.getOrDefault(category, category);
    }

    private static final int MATERIAL_CATEGORY_TOTAL = MATERIAL_NAME_KO.size();
    private static final double CONFIDENCE_CONFIRMED_MIN = 0.7;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECT_MAP_LIST = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PlanningDashboardRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ===== 전략 대시보드 =====

    /** 이번 분기 미확인(unacknowledged) 구매 리스크 평가를 사업부별로 누적 평균한다. */
    public List<PlanningDashboardDto.RiskExposureByUnit> loadRiskExposureByUnit() {
        return jdbc.query("""
                SELECT bu.business_unit_id, bu.name, AVG(p.procurement_risk_score) AS exposure_score
                FROM procurement_risk_assessments p
                JOIN material_category_business_units cb ON cb.material_category = p.material_category
                JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                WHERE p.material_category IS NOT NULL
                  AND p.assessed_at >= date_trunc('quarter', now())
                  AND NOT EXISTS (
                      SELECT 1 FROM procurement_risk_acknowledgements a
                      WHERE a.assessment_id = p.assessment_id)
                GROUP BY bu.business_unit_id, bu.name
                ORDER BY bu.business_unit_id
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.RiskExposureByUnit(
                rs.getString("name"),
                rs.getBigDecimal("exposure_score")));
    }

    /** 공급사별 최근 90일 리스크 이력. analyses.supplier_id 직접 FK로 집계(이름 매칭 불필요). */
    public List<PlanningDashboardDto.VendorRiskHistoryItem> loadVendorRiskHistory(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query("""
                WITH ranked AS (
                    SELECT a.supplier_id, a.severity, a.mock, a.confidence, a.created_at,
                           ROW_NUMBER() OVER (PARTITION BY a.supplier_id ORDER BY a.created_at DESC) AS rn
                    FROM analyses a
                    WHERE a.status = 'COMPLETED' AND a.supplier_id IS NOT NULL
                      AND a.created_at >= now() - INTERVAL '90 days'
                )
                SELECT s.erp_supplier_id, s.supplier_name,
                       COUNT(*) AS risk_count_90d,
                       MAX(r.severity) FILTER (WHERE r.rn = 1) AS latest_severity,
                       MAX(CASE WHEN r.mock THEN 1 ELSE 0 END) FILTER (WHERE r.rn = 1) AS latest_mock,
                       MAX(r.confidence) FILTER (WHERE r.rn = 1) AS latest_confidence
                FROM ranked r
                JOIN suppliers s ON s.supplier_id = r.supplier_id
                GROUP BY s.supplier_id, s.erp_supplier_id, s.supplier_name
                ORDER BY risk_count_90d DESC
                LIMIT :limit
                """, params, (rs, rowNum) -> new PlanningDashboardDto.VendorRiskHistoryItem(
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getLong("risk_count_90d"),
                gradeKo(rs.getString("latest_severity")),
                confidenceLabel(rs.getInt("latest_mock") == 1, (Double) rs.getObject("latest_confidence"))));
    }

    public record QuarterKpi(long detectedCount, BigDecimal criticalRatio, BigDecimal avgResponseDays) {}

    /** 이번 분기 탐지 건수/심각 비중/평균 대응 소요(assessed_at→acknowledged_at). */
    public QuarterKpi quarterKpi() {
        return jdbc.queryForObject("""
                WITH quarter AS (
                    SELECT * FROM procurement_risk_assessments
                    WHERE assessed_at >= date_trunc('quarter', now())
                )
                SELECT
                    (SELECT COUNT(*) FROM quarter) AS detected_count,
                    CASE WHEN (SELECT COUNT(*) FROM quarter) = 0 THEN NULL
                         ELSE (SELECT COUNT(*) FROM quarter WHERE procurement_risk_level = 'CRITICAL')::numeric
                              / (SELECT COUNT(*) FROM quarter) * 100
                    END AS critical_ratio,
                    (SELECT AVG(EXTRACT(EPOCH FROM (ack.acknowledged_at - q.assessed_at)) / 86400.0)
                       FROM quarter q JOIN procurement_risk_acknowledgements ack
                            ON ack.assessment_id = q.assessment_id) AS avg_response_days
                """, new MapSqlParameterSource(), (rs, rowNum) -> new QuarterKpi(
                rs.getLong("detected_count"),
                rs.getBigDecimal("critical_ratio"),
                rs.getBigDecimal("avg_response_days")));
    }

    // ===== 자재 위험 =====

    private record MaterialSeverityRow(
            String materialName, String businessUnitName,
            String severity, BigDecimal score, BigDecimal safetyStockDays) {}

    private static final String LATEST_MATERIAL_SEVERITY_CTE = """
            WITH latest AS (
                SELECT DISTINCT ON (material_id) material_id, severity, score, safety_stock_days
                FROM severity_assessments
                ORDER BY material_id, created_at DESC
            )
            """;

    private List<MaterialSeverityRow> loadLatestMaterialSeverity() {
        return jdbc.query(LATEST_MATERIAL_SEVERITY_CTE + """
                SELECT m.material_name, m.business_unit_id, bu.name AS business_unit_name,
                       l.severity, l.score, l.safety_stock_days
                FROM latest l
                JOIN materials m ON m.material_id = l.material_id
                LEFT JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                ORDER BY l.score DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new MaterialSeverityRow(
                rs.getString("material_name"),
                rs.getString("business_unit_name"),
                rs.getString("severity"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("safety_stock_days")));
    }

    public List<PlanningDashboardDto.MaterialRiskRankItem> findMaterialRiskRanking() {
        List<MaterialSeverityRow> rows = loadLatestMaterialSeverity();
        List<PlanningDashboardDto.MaterialRiskRankItem> result = new ArrayList<>();
        int rank = 1;
        for (MaterialSeverityRow row : rows) {
            String grade = gradeKo(row.severity());
            if (grade == null) {
                continue; // UNKNOWN — 아직 분석되지 않은 자재는 랭킹에서 제외
            }
            result.add(new PlanningDashboardDto.MaterialRiskRankItem(
                    row.materialName(), row.score(), rank++, grade));
        }
        return result;
    }

    public List<PlanningDashboardDto.RiskExposureByUnit> loadTopMaterialUnitExposure() {
        Map<String, List<BigDecimal>> scoresByUnit = new LinkedHashMap<>();
        for (MaterialSeverityRow row : loadLatestMaterialSeverity()) {
            if (row.businessUnitName() == null || row.score() == null) {
                continue;
            }
            scoresByUnit.computeIfAbsent(row.businessUnitName(), k -> new ArrayList<>()).add(row.score());
        }
        List<PlanningDashboardDto.RiskExposureByUnit> result = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> entry : scoresByUnit.entrySet()) {
            BigDecimal sum = entry.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(entry.getValue().size()), 1, RoundingMode.HALF_UP);
            result.add(new PlanningDashboardDto.RiskExposureByUnit(entry.getKey(), avg));
        }
        return result;
    }

    public record MaterialRiskKpi(
            long assessedCount, long criticalCount, BigDecimal avgSafetyStockDays, BigDecimal maxScore) {}

    public MaterialRiskKpi loadMaterialRiskKpi() {
        return jdbc.queryForObject(LATEST_MATERIAL_SEVERITY_CTE + """
                SELECT COUNT(*) AS assessed_count,
                       COUNT(*) FILTER (WHERE severity = 'CRITICAL') AS critical_count,
                       AVG(safety_stock_days) AS avg_safety_stock_days,
                       MAX(score) AS max_score
                FROM latest
                """, new MapSqlParameterSource(), (rs, rowNum) -> new MaterialRiskKpi(
                rs.getLong("assessed_count"),
                rs.getLong("critical_count"),
                rs.getBigDecimal("avg_safety_stock_days"),
                rs.getBigDecimal("max_score")));
    }

    /** 이번 분기 vs 지난 분기 평균 점수 비교. 지난 분기 데이터가 없으면 null(중립 문구는 Service가 결정). */
    public BigDecimal[] loadQuarterScoreComparison() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    AVG(score) FILTER (WHERE assessed_at >= date_trunc('quarter', now())) AS current_avg,
                    AVG(score) FILTER (
                        WHERE assessed_at >= date_trunc('quarter', now()) - INTERVAL '3 months'
                          AND assessed_at < date_trunc('quarter', now())
                    ) AS previous_avg
                FROM severity_assessments
                """, new MapSqlParameterSource());
        return new BigDecimal[]{(BigDecimal) row.get("current_avg"), (BigDecimal) row.get("previous_avg")};
    }

    // ===== 수입 의존도 =====

    /** 활성 supplier_materials 전체 기준 국가별 공급 비중(전체 자재 합산, 100% 정규화). */
    public List<PlanningDashboardDto.CountryDependencyItem> loadImportDependencyByCountry() {
        return jdbc.query("""
                WITH active AS (
                    SELECT sm.supply_share_ratio, s.country_code
                    FROM supplier_materials sm
                    JOIN suppliers s ON s.supplier_id = sm.supplier_id
                    WHERE sm.valid_from <= CURRENT_DATE AND (sm.valid_to IS NULL OR sm.valid_to >= CURRENT_DATE)
                )
                SELECT country_code,
                       SUM(supply_share_ratio) / NULLIF((SELECT SUM(supply_share_ratio) FROM active), 0) * 100
                           AS share_ratio
                FROM active
                GROUP BY country_code
                ORDER BY share_ratio DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.CountryDependencyItem(
                rs.getString("country_code"),
                rs.getBigDecimal("share_ratio")));
    }

    public List<PlanningDashboardDto.UnitDependencyItem> loadImportDependencyByUnit() {
        return jdbc.query("""
                WITH active AS (
                    SELECT sm.supply_share_ratio, s.country_code, bu.business_unit_id, bu.name AS bu_name
                    FROM supplier_materials sm
                    JOIN suppliers s ON s.supplier_id = sm.supplier_id
                    JOIN materials m ON m.material_id = sm.material_id
                    JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                    WHERE sm.valid_from <= CURRENT_DATE AND (sm.valid_to IS NULL OR sm.valid_to >= CURRENT_DATE)
                )
                SELECT bu_name, country_code,
                       SUM(supply_share_ratio)
                           / SUM(SUM(supply_share_ratio)) OVER (PARTITION BY business_unit_id) * 100 AS share_ratio
                FROM active
                GROUP BY business_unit_id, bu_name, country_code
                ORDER BY bu_name, share_ratio DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.UnitDependencyItem(
                rs.getString("bu_name"),
                rs.getString("country_code"),
                rs.getBigDecimal("share_ratio")));
    }

    /** 승인된 공급사가 2곳 이상인 활성 자재 수 ÷ 활성 자재 총수 × 100. */
    public BigDecimal loadDiversificationRate() {
        return jdbc.queryForObject("""
                WITH approved_supplier_counts AS (
                    SELECT sm.material_id, COUNT(*) AS approved_count
                    FROM supplier_materials sm
                    WHERE sm.approved_status = 'APPROVED'
                      AND sm.valid_from <= CURRENT_DATE AND (sm.valid_to IS NULL OR sm.valid_to >= CURRENT_DATE)
                    GROUP BY sm.material_id
                )
                SELECT
                    (SELECT COUNT(*) FROM approved_supplier_counts WHERE approved_count >= 2)::numeric
                    / NULLIF((SELECT COUNT(*) FROM materials WHERE active = TRUE), 0) * 100
                """, new MapSqlParameterSource(), BigDecimal.class);
    }

    // ===== 공급사 분석 =====

    private record SupplierAggregateRow(
            long supplierId, String erpSupplierId, String supplierName,
            long riskCount90d, String latestSeverity) {}

    private List<SupplierAggregateRow> loadSupplierAggregate() {
        return jdbc.query("""
                WITH ranked AS (
                    SELECT a.supplier_id, a.severity, a.created_at,
                           ROW_NUMBER() OVER (PARTITION BY a.supplier_id ORDER BY a.created_at DESC) AS rn
                    FROM analyses a
                    WHERE a.status = 'COMPLETED' AND a.supplier_id IS NOT NULL
                      AND a.created_at >= now() - INTERVAL '90 days'
                )
                SELECT s.supplier_id, s.erp_supplier_id, s.supplier_name,
                       COUNT(*) AS risk_count_90d,
                       MAX(r.severity) FILTER (WHERE r.rn = 1) AS latest_severity
                FROM ranked r
                JOIN suppliers s ON s.supplier_id = r.supplier_id
                GROUP BY s.supplier_id, s.erp_supplier_id, s.supplier_name
                ORDER BY risk_count_90d DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new SupplierAggregateRow(
                rs.getLong("supplier_id"),
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getLong("risk_count_90d"),
                rs.getString("latest_severity")));
    }

    private Map<Long, List<String>> loadSupplierLinkedUnitsBulk() {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sm.supplier_id, bu.name
                FROM supplier_materials sm
                JOIN materials m ON m.material_id = sm.material_id
                JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                GROUP BY sm.supplier_id, bu.name
                """, new MapSqlParameterSource(), (rs, rowNum) -> {
            result.computeIfAbsent(rs.getLong("supplier_id"), k -> new ArrayList<>())
                    .add(rs.getString("name"));
            return null;
        });
        return result;
    }

    public List<PlanningDashboardDto.SupplierRiskRankItem> findSupplierRanking() {
        Map<Long, List<String>> linkedUnits = loadSupplierLinkedUnitsBulk();
        List<PlanningDashboardDto.SupplierRiskRankItem> result = new ArrayList<>();
        for (SupplierAggregateRow row : loadSupplierAggregate()) {
            String approvedStatus = "CRITICAL".equals(row.latestSeverity()) ? "REVIEW" : "APPROVED";
            result.add(new PlanningDashboardDto.SupplierRiskRankItem(
                    row.erpSupplierId(),
                    row.supplierName(),
                    row.riskCount90d(),
                    approvedStatus,
                    linkedUnits.getOrDefault(row.supplierId(), List.of())));
        }
        return result;
    }

    public record AlternativeReviewProgress(long pending, long total) {}

    /** 대체 후보(is_alternative=true)로 등록된 공급사-자재 연결 중 아직 PENDING인 비율. */
    public AlternativeReviewProgress loadAlternativeReviewProgress() {
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) FILTER (WHERE approved_status = 'PENDING') AS pending,
                    COUNT(*) AS total
                FROM supplier_materials
                WHERE is_alternative = TRUE
                """, new MapSqlParameterSource(), (rs, rowNum) -> new AlternativeReviewProgress(
                rs.getLong("pending"), rs.getLong("total")));
    }

    // ===== 계약 현황 =====

    public record ContractKpi(long activeCount, long expiringCount, long documentCount, long ragReadyCount) {}

    public ContractKpi loadContractKpi(int expiringWithinDays) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("withinDays", expiringWithinDays);
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM contracts WHERE status = 'ACTIVE') AS active_count,
                    (SELECT COUNT(*) FROM contracts
                        WHERE end_date IS NOT NULL
                          AND end_date BETWEEN CURRENT_DATE AND CURRENT_DATE + (:withinDays || ' days')::interval)
                        AS expiring_count,
                    (SELECT COUNT(*) FROM contract_documents) AS document_count,
                    (SELECT COUNT(*) FROM contract_documents WHERE processing_status = 'COMPLETED') AS rag_ready_count
                """, params, (rs, rowNum) -> new ContractKpi(
                rs.getLong("active_count"),
                rs.getLong("expiring_count"),
                rs.getLong("document_count"),
                rs.getLong("rag_ready_count")));
    }

    public List<PlanningDashboardDto.ContractCoverageItem> loadContractCoverageByUnit() {
        return jdbc.query("""
                SELECT bu.name, COUNT(*) AS contract_count
                FROM contracts c
                JOIN materials m ON m.material_id = c.material_id
                JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                GROUP BY bu.name
                ORDER BY contract_count DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.ContractCoverageItem(
                rs.getString("name"), rs.getLong("contract_count")));
    }

    public List<PlanningDashboardDto.EntityBadgeItem> findExpiringContracts(int withinDays, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("withinDays", withinDays)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT c.contract_number, c.end_date, bu.name AS business_unit_name,
                       (c.end_date - CURRENT_DATE) AS days_left
                FROM contracts c
                LEFT JOIN materials m ON m.material_id = c.material_id
                LEFT JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                WHERE c.end_date IS NOT NULL
                  AND c.end_date BETWEEN CURRENT_DATE AND CURRENT_DATE + (:withinDays || ' days')::interval
                ORDER BY c.end_date
                LIMIT :limit
                """, params, (rs, rowNum) -> new PlanningDashboardDto.EntityBadgeItem(
                rs.getString("contract_number"),
                rs.getString("contract_number"),
                rs.getString("business_unit_name"),
                new PlanningDashboardDto.Badge("D-" + rs.getLong("days_left"), "warning")));
    }

    // ===== AI 브리핑 =====

    public List<PlanningDashboardDto.RankedBarItem> loadBriefingCountByUnit() {
        return jdbc.query("""
                SELECT bu.name, COUNT(*) AS briefing_count
                FROM analyses a
                JOIN material_category_business_units cb ON cb.material_category = a.material_category
                JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                WHERE a.status = 'COMPLETED' AND a.created_at >= date_trunc('quarter', now())
                GROUP BY bu.name
                ORDER BY bu.name
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.RankedBarItem(
                rs.getString("name"), rs.getBigDecimal("briefing_count"), "건", "neutral"));
    }

    public List<PlanningDashboardDto.BriefingSummaryItem> findRecentBriefings(int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query("""
                SELECT a.analysis_id, a.material_category, a.severity, a.event_title, bu.name AS business_unit_name
                FROM analyses a
                LEFT JOIN material_category_business_units cb ON cb.material_category = a.material_category
                LEFT JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                WHERE a.status = 'COMPLETED' AND a.severity IS NOT NULL
                ORDER BY a.created_at DESC
                LIMIT :limit OFFSET :offset
                """, params, (rs, rowNum) -> {
            String grade = gradeKo(rs.getString("severity"));
            String category = rs.getString("material_category");
            return new PlanningDashboardDto.BriefingSummaryItem(
                    rs.getString("analysis_id"),
                    materialNameKo(category),
                    grade == null ? "주의" : grade,
                    rs.getString("event_title"),
                    rs.getString("business_unit_name"));
        });
    }

    /** {@link #findRecentBriefings}과 같은 모집단(WHERE 조건)의 전체 건수 — 페이지네이션 총 페이지 계산용. */
    public long countRecentBriefings() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM analyses a WHERE a.status = 'COMPLETED' AND a.severity IS NOT NULL
                """, new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /** CRITICAL이면서 이미 처리(acknowledge)된 평가 건수 — "임원 보고 지정" 기본 정의. */
    public long loadExecutiveFlaggedCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM procurement_risk_assessments p
                WHERE p.procurement_risk_level = 'CRITICAL'
                  AND EXISTS (
                      SELECT 1 FROM procurement_risk_acknowledgements a
                      WHERE a.assessment_id = p.assessment_id)
                """, new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    // ===== 데이터 품질 =====

    public record ConfidenceRawRow(boolean mock, Double confidence) {}

    public List<ConfidenceRawRow> loadConfidenceRawRows() {
        return jdbc.query(
                "SELECT mock, confidence FROM analyses WHERE status = 'COMPLETED'",
                new MapSqlParameterSource(),
                (rs, rowNum) -> new ConfidenceRawRow(rs.getBoolean("mock"), (Double) rs.getObject("confidence")));
    }

    public long loadMaterialCategoryCoverageCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT material_category) FROM procurement_risk_assessments
                WHERE material_category IS NOT NULL
                """, new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    public static int materialCategoryTotal() {
        return MATERIAL_CATEGORY_TOTAL;
    }

    public record PipelineHealth(String status, OffsetDateTime lastRunAt) {}

    public PipelineHealth loadPipelineHealth(String component) {
        List<PipelineHealth> rows = jdbc.query(
                "SELECT status, last_run_at FROM pipeline_health_log WHERE component = :component",
                new MapSqlParameterSource("component", component),
                (rs, rowNum) -> new PipelineHealth(
                        rs.getString("status"), rs.getObject("last_run_at", OffsetDateTime.class)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ===== AI 브리핑 상세(드릴다운) =====

    private record BriefingDetailRow(
            String analysisId, String material, String businessUnitName, String severity,
            String eventTitle, String eventContent,
            String briefing, String recommendedActionsJson, String contractFindingsJson,
            String warningsJson, OffsetDateTime assessedAt) {}

    /**
     * {@code analysisId}가 UUID 형식이 아니면(잘못된 id) 빈 결과로 취급 — DB에 잘못된
     * 캐스트를 던져 500을 내는 대신 서비스 레이어가 자연스럽게 404로 변환할 수 있게 한다.
     */
    public PlanningDashboardDto.AiBriefingDetail findBriefingDetail(String analysisId) {
        UUID parsed;
        try {
            parsed = UUID.fromString(analysisId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 브리핑 본문 4종(briefing·recommended_actions·contract_findings·warnings)은
        // V29가 procurement_risk_assessments에서 DROP하고 ai_briefings로 정본을 옮겼다
        // (상류 #19는 V29 이전 스키마 기준이라 p에서 읽는다 — 이 저장소에서는 b가 정답).
        // p는 assessed_at 하나 때문에 유지한다. 두 LEFT JOIN이 겹치면 행이 곱해지므로
        // 정렬을 b 최신 → p 최신 순으로 두어 rows.get(0)이 최신 쌍을 집게 한다.
        List<BriefingDetailRow> rows = jdbc.query("""
                SELECT a.analysis_id, a.material_category, a.severity, a.event_title, a.event_content,
                       bu.name AS business_unit_name,
                       b.briefing_text AS briefing, b.recommended_actions, b.contract_findings,
                       b.warnings, p.assessed_at
                FROM analyses a
                LEFT JOIN material_category_business_units cb ON cb.material_category = a.material_category
                LEFT JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                LEFT JOIN ai_briefings b ON b.analysis_id = a.analysis_id
                LEFT JOIN procurement_risk_assessments p ON p.analysis_id = a.analysis_id
                WHERE a.analysis_id = :analysisId
                ORDER BY b.created_at DESC NULLS LAST, p.created_at DESC NULLS LAST
                """, new MapSqlParameterSource("analysisId", parsed), (rs, rowNum) -> new BriefingDetailRow(
                rs.getString("analysis_id"),
                materialNameKo(rs.getString("material_category")),
                rs.getString("business_unit_name"),
                rs.getString("severity"),
                rs.getString("event_title"),
                rs.getString("event_content"),
                rs.getString("briefing"),
                rs.getString("recommended_actions"),
                rs.getString("contract_findings"),
                rs.getString("warnings"),
                rs.getObject("assessed_at", OffsetDateTime.class)));
        if (rows.isEmpty()) {
            return null;
        }
        BriefingDetailRow row = rows.get(0);
        return new PlanningDashboardDto.AiBriefingDetail(
                row.analysisId(),
                row.material(),
                row.businessUnitName(),
                gradeKo(row.severity()),
                row.eventTitle(),
                row.eventContent(),
                row.briefing(),
                fromJson(row.recommendedActionsJson(), STRING_LIST),
                fromJson(row.contractFindingsJson(), OBJECT_MAP_LIST),
                fromJson(row.warningsJson(), STRING_LIST),
                row.assessedAt());
    }

    // ===== 계약 상세(드릴다운) =====

    private record ContractDetailRow(
            long contractId, String contractNumber, String contractName, String supplierName,
            String materialName, String businessUnitName, String status,
            LocalDate startDate, LocalDate endDate) {}

    public PlanningDashboardDto.ContractDetail findContractDetail(String contractNumber) {
        List<ContractDetailRow> rows = jdbc.query("""
                SELECT c.contract_id, c.contract_number, c.contract_name, s.supplier_name,
                       m.material_name, bu.name AS business_unit_name, c.status, c.start_date, c.end_date
                FROM contracts c
                JOIN suppliers s ON s.supplier_id = c.supplier_id
                LEFT JOIN materials m ON m.material_id = c.material_id
                LEFT JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                WHERE c.contract_number = :contractNumber
                """, new MapSqlParameterSource("contractNumber", contractNumber), (rs, rowNum) -> new ContractDetailRow(
                rs.getLong("contract_id"),
                rs.getString("contract_number"),
                rs.getString("contract_name"),
                rs.getString("supplier_name"),
                rs.getString("material_name"),
                rs.getString("business_unit_name"),
                rs.getString("status"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)));
        if (rows.isEmpty()) {
            return null;
        }
        ContractDetailRow row = rows.get(0);
        List<PlanningDashboardDto.ContractDocumentItem> documents = jdbc.query("""
                SELECT document_id, original_file_name, processing_status, chunk_count
                FROM contract_documents
                WHERE contract_id = :contractId
                ORDER BY document_id
                """, new MapSqlParameterSource("contractId", row.contractId()),
                // document_id는 BIGINT가 아니라 VARCHAR(40)이다(V1, 값 예: ctr_<uuid>).
                // 포팅 원본의 getLong은 문서가 1건이라도 있으면 "Bad value for type long"으로 500.
                (rs, rowNum) -> new PlanningDashboardDto.ContractDocumentItem(
                        rs.getString("document_id"),
                        rs.getString("original_file_name"),
                        rs.getString("processing_status"),
                        rs.getInt("chunk_count")));
        return new PlanningDashboardDto.ContractDetail(
                row.contractNumber(),
                row.contractName(),
                row.supplierName(),
                row.materialName(),
                row.businessUnitName(),
                row.status(),
                row.startDate(),
                row.endDate(),
                documents);
    }

    // ===== 공용 규칙 =====

    /** severity_assessments/procurement_risk_assessments의 영문 등급 → 3단계 한글 등급. UNKNOWN은 null. */
    private static String gradeKo(String severity) {
        if (severity == null) {
            return null;
        }
        return switch (severity) {
            case "CRITICAL" -> "심각";
            case "WARNING" -> "주의";
            case "NORMAL" -> "정상";
            default -> null;
        };
    }

    /**
     * {@code RiskEventService.confidenceLabel}과 동일한 규칙(패키지가 달라 직접 참조 불가,
     * 5줄짜리 단순 규칙이라 중복 정의). mock=true → 참고, confidence<0.7 또는 null → 경고,
     * 그 외 → 확정.
     */
    private static String confidenceLabel(boolean mock, Double confidence) {
        if (mock) {
            return "참고";
        }
        if (confidence == null || confidence < CONFIDENCE_CONFIRMED_MIN) {
            return "경고";
        }
        return "확정";
    }

    /** {@code ProcurementRiskRepository}와 동일한 JSONB 역직렬화 관례. */
    private <T> T fromJson(String value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("브리핑 JSON 역직렬화에 실패했습니다.", e);
        }
    }
}

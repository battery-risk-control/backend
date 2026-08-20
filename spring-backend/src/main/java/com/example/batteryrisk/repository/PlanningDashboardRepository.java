package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 공급사별 최근 90일 리스크 이력. {@code procurement_risk_assessments.erp_supplier_id}로 집계한다.
     *
     * <p><b>analyses.supplier_id는 쓰지 않는다.</b> 뉴스 분석은 국가·자재 단위라 supplier_id가 항상
     * null이라 아무 것도 잡히지 않는다(빈 패널의 원인). 공급사와 연결된 리스크는 멀티에이전트 채점
     * 결과(procurement_risk_assessments)에만 erp_supplier_id로 붙으므로 그쪽을 집계한다.
     *
     * <p>KG 게이트 조기 종료 건(점수 0·NORMAL)은 {@code erp_exposure_score IS NULL}이라 제외한다 —
     * 실제로 ERP 노출을 본 평가만 이력으로 센다(다른 조회의 dedup 방침과 동일).
     *
     * <p>등급은 최신 평가의 {@code procurement_risk_level}, confidence 라벨은 mock이면 "참고",
     * reviewer 검증 통과면 "확정", 아니면 "경고"로 둔다(assessments엔 수치 confidence가 없어
     * review_passed로 대체).
     */
    public List<PlanningDashboardDto.VendorRiskHistoryItem> loadVendorRiskHistory(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query("""
                WITH ranked AS (
                    SELECT p.erp_supplier_id, p.procurement_risk_level, p.mock, p.review_passed, p.created_at,
                           ROW_NUMBER() OVER (PARTITION BY p.erp_supplier_id ORDER BY p.created_at DESC) AS rn
                    FROM procurement_risk_assessments p
                    WHERE p.erp_supplier_id IS NOT NULL
                      AND p.erp_exposure_score IS NOT NULL
                      AND NOT p.mock
                      AND p.created_at >= now() - INTERVAL '90 days'
                )
                SELECT s.erp_supplier_id, s.supplier_name,
                       COUNT(*) AS risk_count_90d,
                       MAX(r.procurement_risk_level) FILTER (WHERE r.rn = 1) AS latest_level,
                       MAX(CASE WHEN r.mock THEN 1 ELSE 0 END) FILTER (WHERE r.rn = 1) AS latest_mock,
                       MAX(CASE WHEN r.review_passed THEN 1 ELSE 0 END) FILTER (WHERE r.rn = 1) AS latest_review_passed
                FROM ranked r
                JOIN suppliers s ON s.erp_supplier_id = r.erp_supplier_id
                GROUP BY s.erp_supplier_id, s.supplier_name
                ORDER BY risk_count_90d DESC
                LIMIT :limit
                """, params, (rs, rowNum) -> new PlanningDashboardDto.VendorRiskHistoryItem(
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getLong("risk_count_90d"),
                gradeKo(rs.getString("latest_level")),
                confidenceLabel(rs.getInt("latest_mock") == 1,
                        rs.getInt("latest_review_passed") == 1 ? Double.valueOf(1.0) : null)));
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

    /**
     * 자재별 최신 위험도. <b>합성 위험 평가(procurement_risk_assessments)</b>를 소스로 쓴다.
     *
     * <p>예전에는 {@code severity_assessments}를 읽었는데, 그 테이블은 BriefingService의 ERP 심각도
     * 평가가 돈 자재만 채워져 실제로는 mock 1건(Nickel)뿐이라 대시보드가 mock 하나로만 떴다.
     * 합성 평가는 앱 전체(1계층·협력사 이력)와 같은 실데이터 소스이고 여러 자재·사업부를 담는다.
     * KG 조기 종료(점수 0·NORMAL)는 {@code erp_exposure_score IS NULL}로, mock은 {@code NOT mock}으로 뺀다.
     *
     * <p>재고일수는 합성 평가에 없어 <b>ERP 재고에서 직접 계산</b>한다 — 자재별 현재고
     * ({@code inventory_snapshots.on_hand_quantity} 합계) / 일 사용량
     * ({@code material_consumptions.average_daily_usage} 합계). 일 사용량이 0/없으면 null.
     */
    private static final String LATEST_MATERIAL_SEVERITY_CTE = """
            WITH assessed AS (
                SELECT DISTINCT ON (p.erp_material_id)
                       p.erp_material_id,
                       p.procurement_risk_level AS severity,
                       p.procurement_risk_score AS score
                FROM procurement_risk_assessments p
                WHERE p.erp_exposure_score IS NOT NULL
                  AND p.erp_material_id IS NOT NULL
                  AND NOT p.mock
                  AND NOT EXISTS (
                      SELECT 1
                      FROM procurement_risk_acknowledgements ack
                      WHERE ack.assessment_id = p.assessment_id
                  )
                ORDER BY p.erp_material_id, p.created_at DESC
            ),
            material_inventory AS (
                SELECT material_id,
                       SUM(on_hand_quantity) AS on_hand,
                       SUM(safety_stock_quantity) AS safety_stock
                FROM inventory_snapshots WHERE is_current GROUP BY material_id
            ),
            material_usage AS (
                SELECT material_id, SUM(average_daily_usage) AS daily_usage
                FROM material_consumptions WHERE is_current GROUP BY material_id
            ),
            latest AS (
                -- safety_stock_days: 현재고 커버리지 일수(on_hand/일사용량) — 화면 "평균 재고일수" 표시값.
                -- safety_stock_target_days: 안전재고 목표 일수(안전재고수량/일사용량) — 색(tone) 판정용.
                SELECT a.erp_material_id, a.severity, a.score,
                       ROUND(inv.on_hand / NULLIF(usg.daily_usage, 0), 1) AS safety_stock_days,
                       ROUND(inv.safety_stock / NULLIF(usg.daily_usage, 0), 1) AS safety_stock_target_days
                FROM assessed a
                JOIN materials m ON m.erp_material_id = a.erp_material_id
                LEFT JOIN material_inventory inv ON inv.material_id = m.material_id
                LEFT JOIN material_usage usg ON usg.material_id = m.material_id
            )
            """;

    private List<MaterialSeverityRow> loadLatestMaterialSeverity() {
        return jdbc.query(LATEST_MATERIAL_SEVERITY_CTE + """
                SELECT m.material_name, m.business_unit_id, bu.name AS business_unit_name,
                       l.severity, l.score, l.safety_stock_days
                FROM latest l
                JOIN materials m ON m.erp_material_id = l.erp_material_id
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
            long assessedCount, long criticalCount, BigDecimal avgSafetyStockDays,
            BigDecimal avgSafetyStockTargetDays, BigDecimal maxScore) {}

    public MaterialRiskKpi loadMaterialRiskKpi() {
        return jdbc.queryForObject(LATEST_MATERIAL_SEVERITY_CTE + """
                SELECT COUNT(*) AS assessed_count,
                       COUNT(*) FILTER (WHERE severity = 'CRITICAL') AS critical_count,
                       AVG(safety_stock_days) AS avg_safety_stock_days,
                       AVG(safety_stock_target_days) AS avg_safety_stock_target_days,
                       MAX(score) AS max_score
                FROM latest
                """, new MapSqlParameterSource(), (rs, rowNum) -> new MaterialRiskKpi(
                rs.getLong("assessed_count"),
                rs.getLong("critical_count"),
                rs.getBigDecimal("avg_safety_stock_days"),
                rs.getBigDecimal("avg_safety_stock_target_days"),
                rs.getBigDecimal("max_score")));
    }

    /** 이번 분기 vs 지난 분기 평균 점수 비교. 지난 분기 데이터가 없으면 null(중립 문구는 Service가 결정). */
    public BigDecimal[] loadQuarterScoreComparison() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    AVG(procurement_risk_score) FILTER (WHERE assessed_at >= date_trunc('quarter', now())) AS current_avg,
                    AVG(procurement_risk_score) FILTER (
                        WHERE assessed_at >= date_trunc('quarter', now()) - INTERVAL '3 months'
                          AND assessed_at < date_trunc('quarter', now())
                    ) AS previous_avg
                FROM procurement_risk_assessments
                WHERE erp_exposure_score IS NOT NULL AND NOT mock
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

    /**
     * 공급사별 최근 90일 리스크 집계. {@code procurement_risk_assessments.erp_supplier_id} 기준.
     *
     * <p>{@code analyses.supplier_id}는 뉴스가 국가·자재 단위라 항상 NULL이라 아무 것도 잡히지 않는다
     * (공급사 분석 대시보드가 빈 원인). 공급사와 연결된 리스크는 멀티에이전트 합성 평가에만
     * erp_supplier_id로 붙으므로 그쪽을 집계한다(협력사 리스크 이력과 동일). KG 조기 종료(점수 0)와
     * mock은 제외한다.
     */
    private List<SupplierAggregateRow> loadSupplierAggregate() {
        return jdbc.query("""
                WITH ranked AS (
                    SELECT p.erp_supplier_id, p.procurement_risk_level, p.created_at,
                           ROW_NUMBER() OVER (PARTITION BY p.erp_supplier_id ORDER BY p.created_at DESC) AS rn
                    FROM procurement_risk_assessments p
                    WHERE p.erp_supplier_id IS NOT NULL
                      AND p.erp_exposure_score IS NOT NULL
                      AND NOT p.mock
                      AND p.created_at >= now() - INTERVAL '90 days'
                )
                SELECT s.supplier_id, s.erp_supplier_id, s.supplier_name,
                       COUNT(*) AS risk_count_90d,
                       MAX(r.procurement_risk_level) FILTER (WHERE r.rn = 1) AS latest_severity
                FROM ranked r
                JOIN suppliers s ON s.erp_supplier_id = r.erp_supplier_id
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

    public List<PlanningDashboardDto.ContractListItem> findAllContracts() {
        return jdbc.query("""
                SELECT c.contract_id, c.contract_number, c.contract_name, s.supplier_name,
                       bu.name AS business_unit_name, c.status, c.end_date,
                       COUNT(d.document_id) AS document_count,
                       COUNT(d.document_id) FILTER (WHERE d.processing_status = 'COMPLETED') AS rag_ready_count
                FROM contracts c
                JOIN suppliers s ON s.supplier_id = c.supplier_id
                LEFT JOIN materials m ON m.material_id = c.material_id
                LEFT JOIN business_units bu ON bu.business_unit_id = m.business_unit_id
                LEFT JOIN contract_documents d ON d.contract_id = c.contract_id
                GROUP BY c.contract_id, c.contract_number, c.contract_name, s.supplier_name,
                         bu.name, c.status, c.end_date
                ORDER BY c.contract_number
                """, new MapSqlParameterSource(), (rs, rowNum) -> {
            long contractId = rs.getLong("contract_id");
            List<PlanningDashboardDto.ContractDocumentItem> documents = jdbc.query("""
                    SELECT document_id, original_file_name, processing_status, chunk_count
                    FROM contract_documents
                    WHERE contract_id = :contractId
                    ORDER BY created_at DESC, document_id DESC
                    LIMIT 1
                    """, new MapSqlParameterSource("contractId", contractId), (drs, drow) ->
                    new PlanningDashboardDto.ContractDocumentItem(
                            drs.getString("document_id"), drs.getString("original_file_name"),
                            drs.getString("processing_status"), drs.getInt("chunk_count")));
            long documentCount = rs.getLong("document_count");
            return new PlanningDashboardDto.ContractListItem(
                    rs.getString("contract_number"), rs.getString("contract_name"),
                    rs.getString("supplier_name"), rs.getString("business_unit_name"),
                    rs.getString("status"), rs.getObject("end_date", LocalDate.class),
                    documentCount > 0, documentCount > 0 && rs.getLong("rag_ready_count") == documentCount,
                    documents);
        });
    }

    // ===== AI 브리핑 =====

    // "AI 브리핑 취합"은 1계층과 동일하게 실제 생성 브리핑(ai_briefings)만 센다 — analyses(브리핑 전
    // 단계)를 세면 멀티에이전트가 아직 안 돈 정상건까지 잡혀 라벨('브리핑')과 데이터('분석')가 어긋난다.
    // ai_briefings.material_category가 직접 있어(V28) analyses 조인 없이 같은 뷰로 사업부 매핑이 된다.
    // 같은 사건 재브리핑은 BRIEFING_DEDUP_KEY로 접어 KPI·목록·총계와 같은 사건 수를 센다.
    public List<PlanningDashboardDto.RankedBarItem> loadBriefingCountByUnit() {
        return jdbc.query("""
                WITH confirmed AS (
                    SELECT b.material_category, b.created_at,
                """ + NewsEventSql.BRIEFING_DEDUP_KEY + """
                           AS dedup_key
                    FROM ai_briefings b
                    WHERE b.created_at >= date_trunc('quarter', now())
                      AND b.analysis_id IS NOT NULL
                      AND b.composite = TRUE
                      AND b.briefing_text IS NOT NULL
                      AND b.review_passed IS TRUE
                    """ + NEWS_BRIEFING_LINK_GUARD + """
                ),
                deduped AS (
                    SELECT material_category,
                           ROW_NUMBER() OVER (PARTITION BY dedup_key ORDER BY created_at DESC) AS rn
                    FROM confirmed
                )
                SELECT bu.name, COUNT(*) AS briefing_count
                FROM deduped d
                JOIN material_category_business_units cb ON cb.material_category = d.material_category
                JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                WHERE d.rn = 1
                GROUP BY bu.name
                ORDER BY bu.name
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PlanningDashboardDto.RankedBarItem(
                rs.getString("name"), rs.getBigDecimal("briefing_count"), "건", "neutral"));
    }

    // 최근 브리핑 목록도 ai_briefings 단독으로 뽑는다(analysis_id 있는 것 = 뉴스 기반 브리핑).
    // 드릴다운 키는 analysis_id 그대로 — 상세(findBriefingDetail)가 이미 ai_briefings 조인이라 그대로 작동.
    /**
     * 확정 뉴스 브리핑은 수집 이벤트(raw_events)에 연결된 것만 인정하는 가드.
     *
     * <p>재분석 등으로 raw_events.triggered_analysis_id가 새 분석을 가리키게 되면, 옛 분석의
     * 브리핑이 <b>고아</b>로 남는다 — 이 브리핑은 title_ko를 못 받아 화면에 영문 원제목으로 뜨고
     * (지도·속보와 어긋남), 어느 raw_event도 안 가리켜 상세에서 "리스크 이벤트를 찾을 수 없습니다"가
     * 나며, KPI(사건 기준)엔 안 잡히는데 브리핑 카운트만 부풀린다(25 vs 24의 원인).
     *
     * <p>{@link ExecutiveDashboardRepository#loadRiskEventCounts}가 "완결 브리핑까지 간 뉴스 사건"을
     * raw_events 연결로 세는 것과 같은 기준으로 목록·총계·KPI를 맞춘다. 계약 브리핑
     * (source_type&lt;&gt;'NEWS')은 원래 raw_events가 없으므로 그대로 유지한다.
     */
    private static final String NEWS_BRIEFING_LINK_GUARD =
            // IS DISTINCT FROM: source_type이 정확히 'NEWS'일 때만 raw_events 연결을 요구하고,
            // 'CONTRACT'·'MATERIAL'·NULL은 그대로 통과시킨다(계약·자재 브리핑은 raw_events가 없다).
            " AND (b.source_type IS DISTINCT FROM 'NEWS'"
            + " OR EXISTS (SELECT 1 FROM raw_events rex"
            + " WHERE rex.triggered_analysis_id = b.analysis_id AND rex.data_type = 'NEWS'))";

    public List<PlanningDashboardDto.BriefingSummaryItem> findRecentBriefings(int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query("""
                WITH confirmed AS (
                    SELECT
                        b.analysis_id,
                        b.material_category,
                        b.material_name,
                        b.procurement_risk_level AS risk_level,
                        b.created_at,
                        COALESCE(
                            NULLIF(BTRIM((
                                SELECT re.title_ko
                                FROM raw_events re
                                WHERE re.triggered_analysis_id = b.analysis_id
                                  AND re.data_type = 'NEWS'
                                ORDER BY re.collected_at DESC
                                LIMIT 1
                            )), ''),
                            NULLIF(BTRIM(b.subject_title), ''),
                            NULLIF(BTRIM(b.source_headline), ''),
                            '제목 없음'
                        ) AS headline,
                    """ + NewsEventSql.BRIEFING_DEDUP_KEY + """
                               AS dedup_key
                    FROM ai_briefings b
                    -- 신뢰도 '확정'(멀티에이전트 종합 완료 composite + 검증 통과 review_passed)만 노출.
                    -- 3계층 최근 브리핑과 같은 기준 — 브리핑까지 다 생성돼 확정된 건만 목록에 올린다.
                    WHERE b.analysis_id IS NOT NULL
                      AND b.composite = TRUE
                      AND b.briefing_text IS NOT NULL
                      AND b.review_passed IS TRUE
                    """ + NEWS_BRIEFING_LINK_GUARD + """
                ),
                -- 같은 사건 재브리핑은 최신 1건만 남긴다(BRIEFING_DEDUP_KEY). 계약·자재는 각각 유지.
                deduped AS (
                    SELECT confirmed.*,
                           ROW_NUMBER() OVER (PARTITION BY dedup_key ORDER BY created_at DESC) AS rn
                    FROM confirmed
                )
                SELECT
                    d.analysis_id, d.material_category, d.material_name, d.risk_level, d.headline,
                    COALESCE(bu.name, '미분류') AS business_unit_name
                FROM deduped d
                LEFT JOIN material_category_business_units cb ON cb.material_category = d.material_category
                LEFT JOIN business_units bu ON bu.business_unit_id = cb.business_unit_id
                WHERE d.rn = 1
                ORDER BY d.created_at DESC
                LIMIT :limit OFFSET :offset
                """, params, (rs, rowNum) -> {
            // 뱃지 등급은 종합 위험등급(procurement_risk_level, ai_briefings에 NOT NULL). NORMAL/WARNING/CRITICAL
            // → 정상/주의/심각. gradeKo가 못 맞추면 '주의'로 폴백.
            String grade = gradeKo(rs.getString("risk_level"));
            String category = rs.getString("material_category");
            String material = rs.getString("material_name");
            return new PlanningDashboardDto.BriefingSummaryItem(
                    rs.getString("analysis_id"),
                    material == null || material.isBlank() ? materialNameKo(category) : material,
                    grade == null ? "주의" : grade,
                    rs.getString("headline"),
                    rs.getString("business_unit_name"));
        });
    }

    /** {@link #findRecentBriefings}과 같은 모집단(확정 브리핑, 사건 중복 제거)의 전체 사건 수 — 페이지네이션 총 페이지 계산용. */
    public long countRecentBriefings() {
        Long count = jdbc.queryForObject(
                """
                    WITH confirmed AS (
                        SELECT
                    """ + NewsEventSql.BRIEFING_DEDUP_KEY + """
                               AS dedup_key
                        FROM ai_briefings b
                        WHERE b.analysis_id IS NOT NULL AND b.composite = TRUE
                          AND b.briefing_text IS NOT NULL AND b.review_passed IS TRUE
                    """ + NEWS_BRIEFING_LINK_GUARD + """
                    )
                    SELECT COUNT(DISTINCT dedup_key) FROM confirmed
                    """,
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /** AI 브리핑 탭 전용 KPI(이번 분기 실제 생성 브리핑 건수 / CRITICAL 비중). 전략 탭 {@link #quarterKpi}와 분리한다. */
    public record AiBriefingKpi(long briefingCount, long normalCount, long warningCount, long criticalCount) {}

    /**
     * 이번 분기 '확정'(멀티에이전트 종합 완료 composite + 검증 통과 review_passed) 브리핑 건수와
     * 그중 정상·주의·심각 비중·건수. 최근 브리핑 목록·사업부별 분포와 같은 확정 기준이라 화면의
     * 모든 숫자가 일치한다(조기종료 등 확정 아닌 건은 어디에도 잡히지 않는다).
     *
     * <p>같은 뉴스 사건이 여러 번 브리핑되면 {@link NewsEventSql#BRIEFING_DEDUP_KEY}로 접어 사건당 최신 1건만
     * 센다(2026-08-19) — 대시보드 메인 "뉴스 사건 수"와 같은 취급. 계약·자재 브리핑은 각각 유지.
     */
    public AiBriefingKpi aiBriefingKpi() {
        return jdbc.queryForObject("""
                WITH quarter AS (
                    SELECT b.procurement_risk_level, b.created_at,
                """ + NewsEventSql.BRIEFING_DEDUP_KEY + """
                           AS dedup_key
                    FROM ai_briefings b
                    WHERE b.created_at >= date_trunc('quarter', now())
                      AND b.analysis_id IS NOT NULL
                      AND b.composite = TRUE
                      AND b.briefing_text IS NOT NULL
                      AND b.review_passed IS TRUE
                    """ + NEWS_BRIEFING_LINK_GUARD + """
                ),
                deduped AS (
                    SELECT procurement_risk_level,
                           ROW_NUMBER() OVER (PARTITION BY dedup_key ORDER BY created_at DESC) AS rn
                    FROM quarter
                )
                SELECT
                    COUNT(*) AS briefing_count,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'NORMAL') AS normal_count,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'WARNING') AS warning_count,
                    COUNT(*) FILTER (WHERE procurement_risk_level = 'CRITICAL') AS critical_count
                FROM deduped WHERE rn = 1
                """, new MapSqlParameterSource(), (rs, rowNum) -> new AiBriefingKpi(
                rs.getLong("briefing_count"),
                rs.getLong("normal_count"), rs.getLong("warning_count"), rs.getLong("critical_count")));
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
            String analysisId, String material, String businessUnitName, String severity, String riskLevel,
            String eventTitle, String eventContent, String summaryKr, String briefingSummaryKr, String sourceUrl,
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
                SELECT a.analysis_id, a.material_category, a.severity,
                       -- 화면 제목은 한국어 번역(raw_events.title_ko) 우선, 없으면 영문 원문.
                       COALESCE(NULLIF(BTRIM(re.title_ko), ''), a.event_title) AS event_title,
                       a.event_content, a.summary_kr, b.briefing_summary_kr,
                       -- 원문 링크는 분석(analyses)에 없으면 수집 원본(raw_events)에서 보완한다.
                       COALESCE(a.source_url, re.source_url) AS source_url,
                       bu.name AS business_unit_name,
                       b.briefing_text AS briefing, b.recommended_actions, b.contract_findings,
                       b.warnings, p.assessed_at, p.procurement_risk_level AS risk_level
                FROM analyses a
                LEFT JOIN raw_events re ON re.triggered_analysis_id = a.analysis_id
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
                rs.getString("risk_level"),
                rs.getString("event_title"),
                rs.getString("event_content"),
                rs.getString("summary_kr"),
                rs.getString("briefing_summary_kr"),
                rs.getString("source_url"),
                rs.getString("briefing"),
                rs.getString("recommended_actions"),
                rs.getString("contract_findings"),
                rs.getString("warnings"),
                rs.getObject("assessed_at", OffsetDateTime.class)));
        if (rows.isEmpty()) {
            return null;
        }
        BriefingDetailRow row = rows.get(0);
        // 뱃지 등급은 종합 위험등급(procurement_risk_level)을 우선한다 — 종합등급이 아직 없을 때만
        // 외부신호(severity)로 폴백한다. 1계층·브리핑 본문과 같은 종합 축을 쓰게 하기 위함이다.
        String grade = gradeKo(row.riskLevel());
        if (grade == null) {
            grade = gradeKo(row.severity());
        }
        return new PlanningDashboardDto.AiBriefingDetail(
                row.analysisId(),
                row.material(),
                row.businessUnitName(),
                grade,
                row.eventTitle(),
                row.eventContent(),
                row.summaryKr(),
                row.briefingSummaryKr(),
                row.sourceUrl(),
                row.briefing(),
                fromJson(row.recommendedActionsJson(), STRING_LIST),
                fromJson(row.contractFindingsJson(), OBJECT_MAP_LIST),
                fromJson(row.warningsJson(), STRING_LIST),
                row.assessedAt());
    }

    /**
     * 상세 화면용 자세한 요약을 최신 ai_briefings 행에 캐시한다. analysisId가 UUID가 아니거나
     * 대상 브리핑이 없으면 아무것도 하지 않는다(브리핑이 없는 분석은 상세 요약을 붙일 곳이 없다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBriefingSummary(String analysisId, String summaryKr) {
        UUID parsed;
        try {
            parsed = UUID.fromString(analysisId);
        } catch (IllegalArgumentException e) {
            return;
        }
        jdbc.update("""
                UPDATE ai_briefings SET briefing_summary_kr = :summary
                WHERE briefing_id = (
                    SELECT briefing_id FROM ai_briefings
                    WHERE analysis_id = :analysisId
                    ORDER BY created_at DESC
                    LIMIT 1
                )
                """, new MapSqlParameterSource()
                .addValue("summary", summaryKr)
                .addValue("analysisId", parsed));
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
                ORDER BY created_at DESC, document_id DESC
                LIMIT 1
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

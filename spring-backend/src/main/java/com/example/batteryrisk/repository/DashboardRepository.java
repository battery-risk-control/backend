package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.DashboardDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 14단계 조회·집계 전용 Repository. 화면이 바로 쓸 수 있는 형태로 PostgreSQL에서 집계한다. */
@Repository
public class DashboardRepository {

    /** 자재별 최신 Severity 1건만 남기는 공통 CTE. 현재 상태 집계의 기준이다. */
    private static final String LATEST_ASSESSMENT_CTE = """
            WITH latest AS (
                SELECT DISTINCT ON (material_id)
                    material_id, erp_material_id, severity, score, inventory_days,
                    safety_stock_days, supplier_dependency_ratio, feoc_status,
                    data_quality_status, assessed_at, created_at
                FROM severity_assessments
                ORDER BY material_id, created_at DESC
            )
            """;

    /**
     * 자재 대분류(8종)별 최신 구매 리스크 평가(멀티에이전트) 1건만 남기는 CTE + 최근
     * 24시간 raw 활동량 CTE.
     *
     * <p>{@code latest}는 {@code erp_material_id}가 아니라 {@code material_category}로
     * 접는다 — V18 마이그레이션이 "화면 카드가 이 단위라 접기 인덱스도 여기에 건다"고
     * 명시했고, {@code erp_material_id}는 분석에 ERP 연결이 없으면 NULL이라 이 용도에
     * 부적합하다. {@code idx_pra_category_created (material_category, created_at DESC)}
     * 인덱스가 이미 이 조회를 뒷받침한다. {@code procurement_risk_acknowledgements}(V20)에
     * "완료 처리"된 assessment_id는 제외한다 — 그 평가가 그 카테고리의 최신이었다면 카테고리
     * 자체가 자연스럽게 집계에서 빠지고, 새 평가(새 assessment_id)가 들어오면 로그에 없으므로
     * 자동으로 다시 잡힌다.
     *
     * <p><b>{@link NewsEventSql 완결 NEWS 사건}으로 원천을 좁힌다.</b> 좁히지 않으면 계약·자재
     * 화면이 만든 평가와 리스크 모니터링의 ERP 영향 분석 결과가 같은 테이블에 섞여 들어와,
     * 그중 가장 최근 것이 그 자재의 뉴스 등급을 덮어쓴다(실측 2026-08-03: 리튬의 뉴스 브리핑은
     * 주의 48점인데 3시간 뒤 만들어진 계약 브리핑 정상 23점이 KPI를 차지해 지도는 주의 3,
     * KPI는 주의 2가 됐다).
     *
     * <p><b>사건으로 접지 않는다.</b> 여기서 세는 단위는 사건이 아니라 자재 대분류이고 어차피
     * 대분류당 1건만 남으므로 접을 필요가 없다. 오히려 먼저 접으면 대표로 뽑힌 분석에 평가 행이
     * 없을 때 그 자재가 통째로 빠진다(실측: 코발트의 최신 분석 a391238a에는 평가 행이 없다).
     * 사건 단위로 세는 것은 지도와 주요 알림이고, 그쪽과 숫자가 달라도 정상이다 — 라벨을
     * "심각/주의 <b>원자재 N종</b>"으로 두는 이유다.
     *
     * <p>{@code recent_24h}는 카테고리가 아니라 <b>사건</b>으로 접는다. "지난 24시간에 무슨 일이
     * 있었나"를 보여주는 별개 모집단이라 완료 처리 여부는 보지 않지만, 같은 사건을 두 번 세면
     * 화면에 보이는 것과 어긋난다 — 실측 2026-08-03: 같은 DRC-르완다 기사가 3시간 간격으로 두 번
     * 평가돼 평가 행이 2개라, 눈에 보이는 사건은 2건인데 24시간 줄은 3건이었다. 사건 안에서는
     * 가장 최근 평가를 남긴다. 평균 점수도 같이 접힌다 — 반복 평가된 사건이 평균을 두 번 끌면
     * 그것도 같은 종류의 왜곡이다.
     *
     * <p>원천 제한은 {@code latest}와 같이 건다. 두 줄이 같은 칸에 위아래로 붙어 나가는데
     * 모집단의 정의까지 다르면 비교할 수 없는 숫자가 된다.
     *
     * <p>단위는 위아래가 다르다 — 큰 숫자는 자재 종수, 24시간 줄은 사건 건수다. 화면이 "2종"과
     * "24h 2건"으로 단위를 나눠 붙이므로 둘을 빼서 증감으로 읽지 않는다.
     */
    private static final String LATEST_PROCUREMENT_ASSESSMENT_CTE = "WITH "
            + NewsEventSql.COMPLETED_NEWS_CTE + """
            ,
            latest AS (
                SELECT DISTINCT ON (p.material_category)
                    p.material_category, p.procurement_risk_level, p.erp_exposure_score,
                    p.external_signal_score, p.review_passed, p.assessed_at, p.created_at
                FROM procurement_risk_assessments p
                WHERE p.material_category IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM completed_news cn WHERE cn.analysis_id = p.analysis_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM procurement_risk_acknowledgements a
                      WHERE a.assessment_id = p.assessment_id
                  )
                ORDER BY p.material_category, p.created_at DESC
            ),
            recent_24h AS (
                SELECT DISTINCT ON (cn.event_key)
                    p.procurement_risk_level, p.erp_exposure_score, p.external_signal_score
                FROM procurement_risk_assessments p
                JOIN completed_news cn ON cn.analysis_id = p.analysis_id
                WHERE p.material_category IS NOT NULL
                  AND p.created_at >= NOW() - INTERVAL '24 hours'
                ORDER BY cn.event_key, p.created_at DESC
            )
            """;

    /**
     * <b>완결 NEWS 사건별 대표 평가 1건.</b> 원자재 점수와 "주요 이슈"가 같은 모집단을 쓰게 하는
     * 공통 CTE다 — 둘을 따로 조회하던 때는 점수가 평가 행 단위, 이슈가 뉴스 단위라 "48.0점인데
     * 근거 뉴스는 1건"처럼 서로 설명하지 못하는 조합이 나왔다.
     *
     * <p><b>사건당 1건으로 접는 것이 핵심이다.</b> 같은 뉴스가 자동 트리거·스케줄러·수동 실행으로
     * 여러 번 평가되므로 평가 행을 그대로 쓰면 한 사건이 상위 3칸을 독점해 평균을 혼자 결정한다
     * (실측 2026-08-03: 리튬 48.0은 "lithium mine halt in Chile" 한 건의 평가 3개 평균이었고,
     * 코발트 57.0도 같은 DRC-르완다 기사 2개 평균이었다). 접고 나면 중복 수집이 늘어도 점수가
     * 움직이지 않고, 서로 다른 사건이 들어와야 움직인다.
     *
     * <p>사건 안에서는 <b>가장 최근 평가</b>를 대표로 남긴다. 같은 사건을 다시 평가했다면 그건
     * 대안이 아니라 같은 것의 새 판이다.
     *
     * <p>{@code completed_news}를 접기 전 상태로 조인하는 이유는 {@link NewsEventSql}에 적어 뒀다 —
     * 사건의 대표 분석에 평가 행이 없을 수 있어서다.
     */
    private static final String NEWS_EVENT_ASSESSMENT_CTE = "WITH "
            + NewsEventSql.COMPLETED_NEWS_CTE + """
            ,
            live_events AS (
                SELECT DISTINCT ON (cn.event_key)
                       cn.event_key, cn.display_title, cn.collected_at,
                       p.assessment_id, p.material_category, p.procurement_risk_score,
                       p.procurement_risk_level, p.assessed_at, p.created_at
                FROM completed_news cn
                JOIN procurement_risk_assessments p ON p.analysis_id = cn.analysis_id
                WHERE p.material_category IS NOT NULL
                  AND p.procurement_risk_score IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM procurement_risk_acknowledgements a
                      WHERE a.assessment_id = p.assessment_id
                  )
                ORDER BY cn.event_key, p.created_at DESC
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardDto.Summary loadSummary() {
        return jdbc.queryForObject(LATEST_ASSESSMENT_CTE + """
                SELECT
                    (SELECT COUNT(*) FROM latest) AS assessed_material_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'CRITICAL') AS critical_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'WARNING') AS warning_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'NORMAL') AS normal_count,
                    (SELECT COUNT(*) FROM latest WHERE severity = 'UNKNOWN') AS unknown_count,
                    (SELECT MAX(assessed_at) FROM latest) AS latest_assessed_at,
                    -- V6 briefings가 아니라 ai_briefings를 센다. 전자는 BriefingRepository.save에
                    -- 호출자가 하나도 없는 죽은 테이블이라 멀티에이전트 브리핑이 아무리 쌓여도
                    -- 이 KPI가 움직이지 않았다(실측: briefings 1건 vs ai_briefings 24건).
                    (SELECT COUNT(*) FROM ai_briefings) AS briefing_count,
                    (SELECT COUNT(*) FROM materials WHERE active = TRUE) AS material_count,
                    (SELECT COUNT(*) FROM suppliers WHERE active = TRUE) AS supplier_count,
                    (SELECT COUNT(*) FROM contracts) AS contract_count,
                    (SELECT COUNT(*) FROM contract_documents) AS document_count
                """, new MapSqlParameterSource(), (rs, rowNumber) -> new DashboardDto.Summary(
                rs.getLong("assessed_material_count"),
                rs.getLong("critical_count"),
                rs.getLong("warning_count"),
                rs.getLong("normal_count"),
                rs.getLong("unknown_count"),
                rs.getLong("briefing_count"),
                rs.getLong("material_count"),
                rs.getLong("supplier_count"),
                rs.getLong("contract_count"),
                rs.getLong("document_count"),
                rs.getObject("latest_assessed_at", OffsetDateTime.class),
                true));
    }

    public DashboardDto.ProcurementRiskSummary loadProcurementRiskSummary() {
        return jdbc.queryForObject(LATEST_PROCUREMENT_ASSESSMENT_CTE + """
                SELECT
                    (SELECT COUNT(*) FROM latest) AS assessed_category_count,
                    (SELECT COUNT(*) FROM latest WHERE procurement_risk_level = 'CRITICAL') AS critical_count,
                    (SELECT COUNT(*) FROM latest WHERE procurement_risk_level = 'WARNING') AS warning_count,
                    (SELECT COUNT(*) FROM latest WHERE procurement_risk_level = 'NORMAL') AS normal_count,
                    (SELECT AVG(erp_exposure_score) FROM latest) AS erp_exposure_score_avg,
                    (SELECT AVG(external_signal_score) FROM latest) AS external_signal_score_avg,
                    (SELECT COUNT(*) FROM latest WHERE review_passed = TRUE) AS verified_briefing_count,
                    (SELECT MAX(assessed_at) FROM latest) AS latest_assessed_at,
                    (SELECT COUNT(*) FROM recent_24h WHERE procurement_risk_level = 'CRITICAL') AS critical_count_24h,
                    (SELECT COUNT(*) FROM recent_24h WHERE procurement_risk_level = 'WARNING') AS warning_count_24h,
                    (SELECT AVG(erp_exposure_score) FROM recent_24h) AS erp_exposure_score_avg_24h,
                    (SELECT AVG(external_signal_score) FROM recent_24h) AS external_signal_score_avg_24h
                """, new MapSqlParameterSource(), (rs, rowNumber) -> new DashboardDto.ProcurementRiskSummary(
                rs.getLong("assessed_category_count"),
                rs.getLong("critical_count"),
                rs.getLong("warning_count"),
                rs.getLong("normal_count"),
                rs.getBigDecimal("erp_exposure_score_avg"),
                rs.getBigDecimal("external_signal_score_avg"),
                rs.getLong("verified_briefing_count"),
                rs.getObject("latest_assessed_at", OffsetDateTime.class),
                rs.getLong("critical_count_24h"),
                rs.getLong("warning_count_24h"),
                rs.getBigDecimal("erp_exposure_score_avg_24h"),
                rs.getBigDecimal("external_signal_score_avg_24h"),
                true));
    }

    /**
     * 대시보드 "원자재별 리스크 요약" 7종.
     *
     * <p>화면에 항상 7행이 나와야 하므로 목록은 상수({@code SUMMARY_CATEGORIES})로 고정하고
     * 평가를 LEFT JOIN한다 — 평가가 있는 자재만 보여주면 "리튬만 위험하다"인지 "나머지는 아직
     * 못 봤다"인지 구분되지 않는다. {@code RARE_EARTH}는 {@code materials}에 있지만 배터리
     * 원자재 7종에 들지 않아 뺐다.
     *
     * <p>대분류당 <b>점수 상위 3건</b>을 골라 평균을 내고 등급은 그중 최고를 쓴다. 완료 처리된
     * 평가({@code procurement_risk_acknowledgements})는 KPI와 같은 기준으로 제외한다.
     *
     * <p>세는 단위는 평가 행이 아니라 <b>사건</b>이다 — {@link #NEWS_EVENT_ASSESSMENT_CTE} 참고.
     * "주요 이슈"({@link #findMaterialRiskTopNews})와 같은 CTE를 쓰므로 표에 보이는 뉴스와 점수를
     * 만든 뉴스가 항상 일치한다.
     *
     * <p>{@code risk_score_24h_ago}는 24시간 전까지 쌓여 있던 평가만으로 같은 계산을 한 값이다.
     * "어제 같은 시각의 스냅샷"이 따로 저장되지 않으므로 이 방식으로 재구성한다 — 그때까지
     * 평가가 없던 자재는 null이 되고, 화면은 증감을 그리지 않는다(0으로 채우면 "변화 없음"으로
     * 잘못 읽힌다). 여기도 사건 단위로 접는다. 접기 기준이 현재 값과 다르면 증감이 "점수가
     * 변했다"가 아니라 "세는 방법이 변했다"를 보여주게 된다.
     */
    public List<DashboardDto.MaterialRiskSummaryItem> findMaterialRiskSummary() {
        return jdbc.query(NEWS_EVENT_ASSESSMENT_CTE + """
                ,
                categories(material_category, material_name) AS (
                    VALUES ('ALUMINUM','알루미늄'), ('COBALT','코발트'), ('COPPER','구리'),
                           ('GRAPHITE','흑연'), ('LITHIUM','리튬'), ('MANGANESE','망간'),
                           ('NICKEL','니켈')
                ),
                ranked AS (
                    SELECT live_events.*, ROW_NUMBER() OVER (
                               PARTITION BY material_category
                               ORDER BY procurement_risk_score DESC, created_at DESC
                           ) AS score_rank,
                           ROW_NUMBER() OVER (
                               PARTITION BY material_category ORDER BY created_at DESC
                           ) AS recency_rank
                    FROM live_events
                ),
                top3 AS (SELECT * FROM ranked WHERE score_rank <= 3),
                agg AS (
                    SELECT material_category,
                           AVG(procurement_risk_score) AS risk_score,
                           MAX(CASE procurement_risk_level
                                   WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END) AS level_rank
                    FROM top3 GROUP BY material_category
                ),
                past_events AS (
                    SELECT DISTINCT ON (cn.event_key)
                           cn.event_key, p.material_category, p.procurement_risk_score, p.created_at
                    FROM completed_news cn
                    JOIN procurement_risk_assessments p ON p.analysis_id = cn.analysis_id
                    WHERE p.material_category IS NOT NULL
                      AND p.procurement_risk_score IS NOT NULL
                      AND p.created_at <= NOW() - INTERVAL '24 hours'
                    ORDER BY cn.event_key, p.created_at DESC
                ),
                past AS (
                    SELECT material_category, AVG(procurement_risk_score) AS risk_score
                    FROM (
                        SELECT material_category, procurement_risk_score,
                               ROW_NUMBER() OVER (
                                   PARTITION BY material_category
                                   ORDER BY procurement_risk_score DESC, created_at DESC
                               ) AS score_rank
                        FROM past_events
                    ) old WHERE score_rank <= 3
                    GROUP BY material_category
                ),
                -- 완료 처리 버튼이 걸릴 행. 표가 보여주는 사건들 중 가장 최근 것이다.
                newest AS (
                    SELECT material_category, assessment_id
                    FROM ranked WHERE recency_rank = 1
                )
                -- 소수 첫째 자리까지만 내보낸다. AVG가 48.333333333333336 같은 값을 주는데
                -- 화면이 "리스크 지수 48.3"을 보여주는 마당에 그 정밀도는 의미가 없고,
                -- 증감(score_delta)도 3.6666666666666665처럼 나와 그대로는 못 쓴다.
                SELECT c.material_category, c.material_name,
                       ROUND(a.risk_score, 1) AS risk_score,
                       CASE a.level_rank WHEN 3 THEN 'CRITICAL' WHEN 2 THEN 'WARNING'
                                         WHEN 1 THEN 'NORMAL' END AS risk_level,
                       ROUND(pa.risk_score, 1) AS risk_score_24h_ago,
                       ROUND(a.risk_score - pa.risk_score, 1) AS score_delta,
                       n.assessment_id AS latest_assessment_id
                FROM categories c
                LEFT JOIN agg a  ON a.material_category = c.material_category
                LEFT JOIN past pa ON pa.material_category = c.material_category
                LEFT JOIN newest n ON n.material_category = c.material_category
                ORDER BY a.risk_score DESC NULLS LAST, c.material_name
                """, new MapSqlParameterSource(), (rs, rowNumber) -> new DashboardDto.MaterialRiskSummaryItem(
                rs.getString("material_category"),
                rs.getString("material_name"),
                rs.getBigDecimal("risk_score"),
                rs.getString("risk_level"),
                rs.getBigDecimal("risk_score_24h_ago"),
                rs.getBigDecimal("score_delta"),
                rs.getObject("latest_assessment_id", java.util.UUID.class),
                List.of()));
    }

    /**
     * {@link #findMaterialRiskSummary()}의 "주요 이슈" 칸에 붙일 상위 3건.
     *
     * <p>제목은 {@code raw_events.title_ko}(번역본)를 우선하고 없으면 {@code analyses.event_title}
     * (영문 원문)로 떨어진다 — 지도·속보와 같은 규칙이라 화면끼리 같은 뉴스를 다른 제목으로
     * 부르지 않는다.
     *
     * <p>요약 조회와 쿼리를 나눈 이유는 1:N 조인 결과를 한 번에 매핑하면 집계 행이 뉴스 수만큼
     * 복제돼 평균이 어긋나기 때문이다. 대분류 7개 × 최대 3건이라 두 번째 조회는 21행이 상한이다.
     *
     * <p><b>{@link #findMaterialRiskSummary()}의 상위 3건과 같은 CTE·같은 정렬을 쓴다.</b> 쿼리는
     * 나뉘어 있지만 고르는 대상은 한 벌이어야 한다 — 다르면 "점수를 만든 뉴스"와 "근거로 보여주는
     * 뉴스"가 어긋나 표를 설명할 수 없게 된다. 사건 단위로 접는 규칙도 그 CTE에 있다.
     */
    public List<DashboardDto.MaterialRiskNewsItem> findMaterialRiskTopNews(String materialCategory) {
        return jdbc.query(NEWS_EVENT_ASSESSMENT_CTE + """
                SELECT assessment_id, procurement_risk_score, procurement_risk_level,
                       assessed_at, display_title AS title
                FROM live_events
                WHERE material_category = :materialCategory
                ORDER BY procurement_risk_score DESC, created_at DESC
                LIMIT 3
                """,
                new MapSqlParameterSource().addValue("materialCategory", materialCategory),
                (rs, rowNumber) -> new DashboardDto.MaterialRiskNewsItem(
                        rs.getObject("assessment_id", java.util.UUID.class),
                        rs.getString("title"),
                        rs.getBigDecimal("procurement_risk_score"),
                        rs.getString("procurement_risk_level"),
                        rs.getObject("assessed_at", OffsetDateTime.class)));
    }

    /**
     * 수집 이벤트에 연결되지 않은 완결 NEWS 브리핑 수. <b>데이터 품질 점검용</b>이다.
     *
     * <p>{@link NewsEventSql}이 이런 브리핑을 운영 화면에서 제외하는데, 조용히 빼면 "화면에 왜
     * 없는지"를 알 방법이 없다. 제외한 만큼을 여기서 세어 {@code DashboardService}가 로그로
     * 남긴다 — 시연 시드나 수동 {@code /analyze} 호출로 만들어진 분석이 쌓이고 있다는 신호다.
     *
     * <p>실측 2026-08-03 기준 4건이며 전부 LITHIUM/CL이다. 이것들 때문에 지도에는 리튬 주의가
     * 뜨는데 목록·알림에는 없는 상태가 만들어졌다.
     */
    public long countOrphanNewsBriefings() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM ai_briefings b
                WHERE b.source_type = 'NEWS'
                  AND b.composite = TRUE
                  AND NULLIF(BTRIM(b.briefing_text), '') IS NOT NULL
                  AND b.review_passed = TRUE
                  AND NOT EXISTS (
                      SELECT 1 FROM raw_events r WHERE r.triggered_analysis_id = b.analysis_id
                  )
                """, new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * "완료 처리"된 평가 목록. 되돌리기 UI가 서는 유일한 자리다.
     *
     * <p>완료 처리하면 그 평가가 KPI·주요 이슈에서 빠지는데, 빠지는 순간 화면에서 사라져
     * 되돌릴 버튼을 놓을 곳도 함께 사라졌다. 여기서 다시 꺼내 보여준다.
     *
     * <p>제목은 {@code raw_events.title_ko}(번역본)를 우선하고 없으면 {@code analyses.event_title}
     * 로 떨어진다 — 지도·속보·주요 이슈와 같은 규칙이라 화면끼리 같은 뉴스를 다른 제목으로
     * 부르지 않는다.
     *
     * <p>{@code users}는 LEFT JOIN이다. 계정이 지워져도 완료 기록 자체는 남아야 하고(FK가
     * 있지만 그건 지금 시점의 보장일 뿐이다), 이름이 없다고 목록에서 빠지면 되돌릴 방법이
     * 사라진다.
     */
    public List<DashboardDto.AcknowledgedItem> findAcknowledged(int limit) {
        return jdbc.query("""
                -- 표기명은 findMaterialRiskSummary와 같은 표를 쓴다. 자바 쪽 매핑
                -- (RiskEventService.materialNameKo)은 service 패키지 밖에서 못 보고,
                -- 표를 두 벌로 두면 화면마다 자재 이름이 갈린다.
                WITH categories(material_category, material_name) AS (
                    VALUES ('ALUMINUM','알루미늄'), ('COBALT','코발트'), ('COPPER','구리'),
                           ('GRAPHITE','흑연'), ('LITHIUM','리튬'), ('MANGANESE','망간'),
                           ('NICKEL','니켈')
                )
                SELECT p.assessment_id, p.material_category,
                       -- 표에 없는 대분류(RARE_EARTH 등)는 코드를 그대로 보여준다. 지어내지 않는다.
                       COALESCE(c.material_name, p.material_category) AS material_name,
                       p.procurement_risk_level, p.procurement_risk_score,
                       COALESCE(r.title_ko, an.event_title) AS subject_title,
                       u.name AS acknowledged_by_name,
                       a.acknowledged_at
                FROM procurement_risk_acknowledgements a
                JOIN procurement_risk_assessments p ON p.assessment_id = a.assessment_id
                LEFT JOIN analyses an ON an.analysis_id = p.analysis_id
                LEFT JOIN raw_events r ON r.triggered_analysis_id = p.analysis_id
                LEFT JOIN users u ON u.id = a.acknowledged_by
                LEFT JOIN categories c ON c.material_category = p.material_category
                ORDER BY a.acknowledged_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("limit", limit),
                (rs, rowNumber) -> new DashboardDto.AcknowledgedItem(
                        rs.getObject("assessment_id", java.util.UUID.class),
                        rs.getString("material_category"),
                        rs.getString("material_name"),
                        rs.getString("procurement_risk_level"),
                        rs.getBigDecimal("procurement_risk_score"),
                        rs.getString("subject_title"),
                        rs.getString("acknowledged_by_name"),
                        rs.getObject("acknowledged_at", OffsetDateTime.class)));
    }

    /** 자재별 현재 리스크. 아직 분석되지 않은 자재는 제외한다. */
    public List<DashboardDto.MaterialRiskItem> findMaterialRisks(String severity, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("severity", severity, Types.VARCHAR)
                .addValue("limit", limit);
        return jdbc.query(LATEST_ASSESSMENT_CTE + """
                SELECT l.erp_material_id, m.material_name, l.severity, l.score,
                       l.inventory_days, l.safety_stock_days, l.supplier_dependency_ratio,
                       l.feoc_status, l.data_quality_status, l.assessed_at
                FROM latest l
                JOIN materials m ON m.material_id = l.material_id
                WHERE (:severity IS NULL OR l.severity = :severity)
                ORDER BY
                    CASE l.severity
                        WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1
                        WHEN 'UNKNOWN' THEN 2 ELSE 3 END,
                    l.score DESC
                LIMIT :limit
                """, params, (rs, rowNumber) -> new DashboardDto.MaterialRiskItem(
                rs.getString("erp_material_id"),
                rs.getString("material_name"),
                rs.getString("severity"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("inventory_days"),
                rs.getBigDecimal("safety_stock_days"),
                rs.getBigDecimal("supplier_dependency_ratio"),
                rs.getString("feoc_status"),
                rs.getString("data_quality_status"),
                rs.getObject("assessed_at", OffsetDateTime.class)));
    }

    public List<DashboardDto.ImportDependencyItem> findImportDependency(
            String erpMaterialId, LocalDate asOfDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("erpMaterialId", erpMaterialId)
                .addValue("asOfDate", asOfDate);
        return jdbc.query("""
                SELECT s.erp_supplier_id, s.supplier_name, s.country_code,
                       sm.supply_share_ratio, sm.approved_status, sm.is_alternative
                FROM supplier_materials sm
                JOIN suppliers s ON s.supplier_id = sm.supplier_id
                JOIN materials m ON m.material_id = sm.material_id
                WHERE m.erp_material_id = :erpMaterialId
                  AND sm.valid_from <= :asOfDate
                  AND (sm.valid_to IS NULL OR sm.valid_to >= :asOfDate)
                ORDER BY sm.supply_share_ratio DESC, sm.priority_rank
                """, params, (rs, rowNumber) -> new DashboardDto.ImportDependencyItem(
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("country_code"),
                rs.getBigDecimal("supply_share_ratio"),
                rs.getString("approved_status"),
                rs.getBoolean("is_alternative")));
    }

    public String findMaterialName(String erpMaterialId) {
        List<String> names = jdbc.query(
                "SELECT material_name FROM materials WHERE erp_material_id = :erpMaterialId",
                new MapSqlParameterSource("erpMaterialId", erpMaterialId),
                (rs, rowNumber) -> rs.getString("material_name"));
        return names.isEmpty() ? null : names.get(0);
    }

    public List<DashboardDto.ContractItem> findContractPage(String status, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query("""
                SELECT c.contract_id, c.erp_contract_id, c.contract_number, c.contract_name,
                       s.erp_supplier_id, s.supplier_name,
                       m.erp_material_id, m.material_name,
                       c.status, c.contract_role, c.start_date, c.end_date
                FROM contracts c
                JOIN suppliers s ON s.supplier_id = c.supplier_id
                LEFT JOIN materials m ON m.material_id = c.material_id
                WHERE (:status IS NULL OR c.status = :status)
                ORDER BY c.contract_id
                LIMIT :limit OFFSET :offset
                """, params, DashboardRepository::mapContract);
    }

    public long countContracts(String status) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contracts
                WHERE (:status IS NULL OR status = :status)
                """, new MapSqlParameterSource().addValue("status", status, Types.VARCHAR), Long.class);
        return count == null ? 0L : count;
    }

    private static DashboardDto.ContractItem mapContract(ResultSet rs, int rowNumber) throws SQLException {
        return new DashboardDto.ContractItem(
                rs.getLong("contract_id"),
                rs.getString("erp_contract_id"),
                rs.getString("contract_number"),
                rs.getString("contract_name"),
                rs.getString("erp_supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("erp_material_id"),
                rs.getString("material_name"),
                rs.getString("status"),
                rs.getString("contract_role"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class));
    }
}

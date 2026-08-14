package com.example.batteryrisk;

import com.example.batteryrisk.dto.PlanningDashboardDto;
import com.example.batteryrisk.repository.PlanningDashboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 2계층 드릴다운 2종({@code findBriefingDetail}·{@code findContractDetail})의 원시 SQL 스모크.
 *
 * <p>이 두 메서드는 문자열 SQL이라 스키마와 어긋나도 컴파일이 통과한다 — 실제로 #19 포팅
 * 직후 {@code findBriefingDetail}이 V29에서 DROP된 {@code procurement_risk_assessments}
 * 본문 4컬럼을 읽어 호출 시에만 500이 났다. 이 테스트는 그런 부류(런타임에만 깨지는 SQL
 * 회귀)를 잡는 최소 안전망이다.
 *
 * <p>테스트 DB는 다른 테스트와 같은 H2(PostgreSQL 모드, Flyway off)다. JPA 엔티티가 아닌
 * 테이블(ai_briefings·business_units 등)은 ddl-auto가 만들어 주지 않으므로, SQL이 실제로
 * 만지는 컬럼만 추린 스탠드인을 여기서 직접 만든다(prod의
 * {@code material_category_business_units}는 뷰지만 조회 대상 컬럼이 같아 테이블로 대신한다).
 * 컬럼 이름·타입이 프로덕션 마이그레이션(V1·V18·V23·V27)과 어긋나면 이 테스트가 아니라
 * 실서버에서 깨지므로, 스탠드인을 고칠 때는 해당 마이그레이션과 대조할 것.
 */
@SpringBootTest
class PlanningDashboardDetailSqlTest {

    @Autowired PlanningDashboardRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private static final UUID ANALYSIS_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUpSchemaAndFixtures() {
        // --- 스탠드인 스키마 (SQL이 만지는 컬럼만, prod 마이그레이션과 이름·타입 일치) ---
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS business_units (
                    business_unit_id BIGINT PRIMARY KEY,
                    name             VARCHAR(100) NOT NULL)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS material_category_business_units (
                    material_category VARCHAR(50) PRIMARY KEY,
                    business_unit_id  BIGINT NOT NULL)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS ai_briefings (
                    analysis_id            UUID,
                    material_category      VARCHAR(50),
                    material_name          VARCHAR(200),
                    procurement_risk_level VARCHAR(20),
                    composite              BOOLEAN,
                    review_passed          BOOLEAN,
                    source_headline        VARCHAR(500),
                    subject_title          VARCHAR(500),
                    briefing_text          TEXT,
                    briefing_summary_kr    TEXT,
                    recommended_actions    TEXT,
                    contract_findings      TEXT,
                    warnings               TEXT,
                    created_at             TIMESTAMP WITH TIME ZONE NOT NULL)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS procurement_risk_assessments (
                    analysis_id            UUID,
                    procurement_risk_level VARCHAR(20),
                    assessed_at            TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at             TIMESTAMP WITH TIME ZONE NOT NULL)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS suppliers (
                    supplier_id   BIGINT PRIMARY KEY,
                    supplier_name VARCHAR(200) NOT NULL)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS materials (
                    material_id      BIGINT PRIMARY KEY,
                    material_name    VARCHAR(200) NOT NULL,
                    business_unit_id BIGINT)""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS contracts (
                    contract_id     BIGINT PRIMARY KEY,
                    contract_number VARCHAR(50) NOT NULL,
                    contract_name   VARCHAR(300),
                    supplier_id     BIGINT NOT NULL,
                    material_id     BIGINT,
                    status          VARCHAR(30),
                    start_date      DATE,
                    end_date        DATE)""");
        // contract_documents는 Document 엔티티가 ddl-auto로 이미 만든다(스탠드인 불필요).

        for (String table : new String[] {
                "ai_briefings", "procurement_risk_assessments", "material_category_business_units",
                "business_units", "contract_documents", "contracts", "materials", "suppliers", "analyses"}) {
            jdbc.getJdbcTemplate().execute("DELETE FROM " + table);
        }

        // --- 브리핑 상세 픽스처 ---
        jdbc.update("""
                INSERT INTO analyses (analysis_id, event_title, event_content, status, mock,
                                      created_at, severity, material_category)
                VALUES (:id, '콩고 코발트 수출 중단', '코발트 공급 차질 우려', 'COMPLETED', false,
                        CURRENT_TIMESTAMP, 'CRITICAL', '코발트')""",
                Map.of("id", ANALYSIS_ID));
        jdbc.update("INSERT INTO business_units (business_unit_id, name) VALUES (1, '배터리셀사업부')",
                Map.of());
        jdbc.update("""
                INSERT INTO material_category_business_units (material_category, business_unit_id)
                VALUES ('코발트', 1)""", Map.of());
        // 브리핑 2건 — 최신 것이 뽑혀야 한다.
        jdbc.update("""
                INSERT INTO ai_briefings (analysis_id, briefing_text, recommended_actions,
                                          contract_findings, warnings, created_at)
                VALUES (:id, '이전 브리핑', '[]', '[]', '[]', :old)""",
                Map.of("id", ANALYSIS_ID, "old", OffsetDateTime.now().minusDays(1)));
        jdbc.update("""
                INSERT INTO ai_briefings (analysis_id, briefing_text, recommended_actions,
                                          contract_findings, warnings, created_at)
                VALUES (:id, '최신 브리핑 본문', '["대체 공급사 검토"]',
                        '[{"contract_id": 1001, "page": 1}]', '["참고 경고"]', :now)""",
                Map.of("id", ANALYSIS_ID, "now", OffsetDateTime.now()));
        // 종합 위험등급(procurement_risk_level)은 WARNING — analyses.severity(CRITICAL, 외부신호 축)와
        // 일부러 다르게 둬, 뱃지 grade가 외부신호가 아니라 종합등급에서 오는지 검증한다.
        jdbc.update("""
                INSERT INTO procurement_risk_assessments (analysis_id, procurement_risk_level,
                                                          assessed_at, created_at)
                VALUES (:id, 'WARNING', :at, :at)""",
                Map.of("id", ANALYSIS_ID, "at", OffsetDateTime.now()));

        // --- 계약 상세 픽스처 ---
        jdbc.update("INSERT INTO suppliers (supplier_id, supplier_name) VALUES (10, '글렌코어')", Map.of());
        jdbc.update("""
                INSERT INTO materials (material_id, material_name, business_unit_id)
                VALUES (20, '수산화코발트', 1)""", Map.of());
        jdbc.update("""
                INSERT INTO contracts (contract_id, contract_number, contract_name, supplier_id,
                                       material_id, status, start_date, end_date)
                VALUES (30, 'CTR-010', '코발트 장기 공급 계약', 10, 20, 'ACTIVE',
                        DATE '2026-01-01', DATE '2027-12-31')""", Map.of());
        // 실스키마와 동일: document_id는 VARCHAR(40) (값 예: ctr_<uuid>) — getLong이면 여기서 터진다.
        jdbc.update("""
                INSERT INTO contract_documents (document_id, contract_id, supplier_id, material_id,
                                                document_type, original_file_name, mime_type,
                                                file_size_bytes, content_hash, file_path,
                                                processing_status, chunk_count, created_at)
                VALUES ('ctr_0a1b2c3d4e5f', 30, 10, 20, 'CONTRACT', 'ctr-010.pdf',
                        'application/pdf', 1024, RPAD('0', 64, '0'), 'contracts/ctr-010/original.pdf',
                        'COMPLETED', 12, CURRENT_TIMESTAMP)""", Map.of());
    }

    @Test
    void briefingDetailReadsBodyFromAiBriefingsAndPicksLatest() {
        PlanningDashboardDto.AiBriefingDetail detail =
                repository.findBriefingDetail(ANALYSIS_ID.toString());

        assertThat(detail).isNotNull();
        // V29 정합의 핵심: 본문 4종이 ai_briefings에서 와야 하고, 2건 중 최신이어야 한다.
        assertThat(detail.briefing()).isEqualTo("최신 브리핑 본문");
        assertThat(detail.recommendedActions()).containsExactly("대체 공급사 검토");
        assertThat(detail.contractFindings()).hasSize(1);
        assertThat(detail.warnings()).containsExactly("참고 경고");
        assertThat(detail.businessUnit()).isEqualTo("배터리셀사업부");
        assertThat(detail.assessedAt()).isNotNull();
        // 뱃지 등급은 종합등급(procurement_risk_level=WARNING→주의)에서 온다.
        // analyses.severity(CRITICAL, 외부신호 축)를 읽으면 '심각'이 나와 1계층·본문과 어긋난다.
        assertThat(detail.grade()).isEqualTo("주의");
    }

    @Test
    void briefingDetailReturnsNullForUnknownOrMalformedId() {
        assertThat(repository.findBriefingDetail(UUID.randomUUID().toString())).isNull();
        assertThat(repository.findBriefingDetail("not-a-uuid")).isNull();
    }

    /**
     * "AI 브리핑 취합"의 분포·목록·총계·KPI가 이제 analyses가 아니라 ai_briefings(실제 생성 브리핑)에서
     * 나오는지 검증한다. analyses 조인 없이 ai_briefings 단독 + 뷰로 사업부·등급·드릴다운 키가 나와야 한다.
     */
    @Test
    void aiBriefingListDistributionAndKpiReadFromAiBriefings() {
        // 상세 픽스처의 ai_briefings 2건은 material_category가 없어 분포에서 빠지므로, 직접 통제한다.
        jdbc.getJdbcTemplate().execute("DELETE FROM ai_briefings");
        UUID a = ANALYSIS_ID;
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID c = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID d = UUID.fromString("44444444-4444-4444-4444-444444444444");
        // 확정(composite=TRUE + review_passed=TRUE) 3건 + 확정 아님(review_passed=FALSE) 1건.
        insertBriefing(a, "코발트", "CRITICAL", true, true, "콩고 코발트 수출 중단");  // 확정 심각
        insertBriefing(b, "코발트", "WARNING", true, true, "코발트 가격 급등");        // 확정 주의
        insertBriefing(c, null, "NORMAL", true, true, "일반 뉴스");                    // 확정 정상, 자재 미상 → 분포 제외
        insertBriefing(d, "코발트", "WARNING", true, false, "검토 대기 뉴스");         // 검토 필요 → 확정 아님(목록 제외)

        // 분포: 확정 기준. 코발트 확정 2건(a·b)이 배터리셀사업부로, 자재 NULL(c)은 뷰 INNER JOIN에서
        // 제외, d(확정 아님)도 제외 → 배터리셀 2건.
        var byUnit = repository.loadBriefingCountByUnit();
        assertThat(byUnit).hasSize(1);
        assertThat(byUnit.get(0).name()).isEqualTo("배터리셀사업부");
        assertThat(byUnit.get(0).value()).isEqualByComparingTo("2");

        // 목록: 확정 3건(a·b·c)만. d(review_passed=false)는 제외 → 주의가 1건뿐이어야 한다.
        var recent = repository.findRecentBriefings(10, 0);
        assertThat(recent).hasSize(3);
        assertThat(recent).extracting(PlanningDashboardDto.BriefingSummaryItem::grade)
                .containsExactlyInAnyOrder("심각", "주의", "정상");
        var cobalt = recent.stream().filter(i -> "코발트".equals(i.material())).findFirst().orElseThrow();
        assertThat(cobalt.businessUnit()).isEqualTo("배터리셀사업부");
        assertThat(cobalt.riskEventId()).isNotBlank();  // 드릴다운 키 = analysis_id

        // 총계: 확정 3건(d 제외)
        assertThat(repository.countRecentBriefings()).isEqualTo(3);

        // KPI: 모두 확정 기준. 이번 분기(확정) 3건, CRITICAL 비중 = 1/3 ≈ 33.3%, 정상 1·주의 1·심각 1
        // (d는 확정이 아니라 이번 분기 건수·주의 건수 어디에도 안 잡힌다)
        var kpi = repository.aiBriefingKpi();
        assertThat(kpi.briefingCount()).isEqualTo(3);
        assertThat(kpi.normalCount()).isEqualTo(1);
        assertThat(kpi.warningCount()).isEqualTo(1);
        assertThat(kpi.criticalCount()).isEqualTo(1);
    }

    private void insertBriefing(
            UUID id, String category, String level, boolean composite, boolean reviewPassed, String headline) {
        jdbc.update("""
                INSERT INTO ai_briefings (analysis_id, material_category, procurement_risk_level, composite, review_passed,
                                          source_headline, subject_title, briefing_text, recommended_actions,
                                          contract_findings, warnings, created_at)
                VALUES (:id, :cat, :level, :composite, :reviewPassed, :headline, :headline, '본문', '[]', '[]', '[]', :now)""",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("cat", category)
                        .addValue("level", level)
                        .addValue("composite", composite)
                        .addValue("reviewPassed", reviewPassed)
                        .addValue("headline", headline)
                        .addValue("now", OffsetDateTime.now()));
    }

    @Test
    void contractDetailJoinsSupplierMaterialUnitAndDocuments() {
        PlanningDashboardDto.ContractDetail detail = repository.findContractDetail("CTR-010");

        assertThat(detail).isNotNull();
        assertThat(detail.contractName()).isEqualTo("코발트 장기 공급 계약");
        assertThat(detail.supplierName()).isEqualTo("글렌코어");
        assertThat(detail.materialName()).isEqualTo("수산화코발트");
        assertThat(detail.businessUnit()).isEqualTo("배터리셀사업부");
        assertThat(detail.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(detail.documents()).hasSize(1);
        assertThat(detail.documents().get(0).documentId()).isEqualTo("ctr_0a1b2c3d4e5f");
        assertThat(detail.documents().get(0).originalFileName()).isEqualTo("ctr-010.pdf");
        assertThat(detail.documents().get(0).chunkCount()).isEqualTo(12);
    }

    @Test
    void contractDetailReturnsNullForUnknownNumber() {
        assertThat(repository.findContractDetail("CTR-없음")).isNull();
    }
}

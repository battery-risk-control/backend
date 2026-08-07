package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.AiBriefingDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 브리핑 화면이 생성한 브리핑을 PostgreSQL {@code ai_briefings}에 저장·조회한다.
 *
 * <p>{@link ProcurementRiskRepository}와 같은 방식이다 — JPA 엔티티 대신 DTO를 직접 다루고
 * JSONB 컬럼은 {@code CAST(:param AS JSONB)}로 넣는다.
 *
 * <p>저장 파라미터를 따로 만들지 않고 {@link AiBriefingDto.BriefingDetail}을 그대로 받는다.
 * 화면에 나가는 모양과 저장하는 모양이 같아야 "생성 직후 화면"과 "다시 열어본 화면"이 어긋나지
 * 않기 때문이다 — 컬럼 값은 전부 이 DTO에서 꺼낼 수 있다.
 */
@Repository
public class AiBriefingRepository {
    private static final Logger log = LoggerFactory.getLogger(AiBriefingRepository.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST = new TypeReference<>() {};
    private static final TypeReference<AiBriefingDto.ErpEvidence> ERP_EVIDENCE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AiBriefingRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(AiBriefingDto.BriefingDetail briefing, String createdBy) {
        replaceSameNewsBriefing(briefing);

        AiBriefingDto.EvidenceChain chain = briefing.evidenceChain();
        AiBriefingDto.VerificationMeta verification = briefing.verification();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("briefingId", briefing.briefingId())
                .addValue("assessmentId", briefing.assessmentId())
                .addValue("sourceType", briefing.sourceType())
                .addValue("sourceRef", briefing.sourceRef())
                .addValue("subjectTitle", briefing.subjectTitle())
                .addValue("newsId", briefing.newsId())
                .addValue("analysisId", briefing.analysisId())
                .addValue("sourceHeadline", briefing.sourceHeadline())
                .addValue("erpMaterialId", briefing.erpMaterialId())
                .addValue("erpSupplierId", briefing.erpSupplierId())
                .addValue("erpContractId", briefing.erpContractId())
                .addValue("contractId", briefing.contractId())
                .addValue("materialName", briefing.materialName())
                .addValue("materialCategory", briefing.materialCategory())
                .addValue("impactDomainFinal", briefing.impactDomain())
                .addValue("externalSignalLevel", level(chain == null ? null : chain.externalSignal()))
                .addValue("externalSignalScore", score(chain == null ? null : chain.externalSignal()))
                .addValue("erpExposureLevel",
                        briefing.erpEvidence() == null ? null : briefing.erpEvidence().exposureLevel())
                .addValue("erpExposureScore",
                        briefing.erpEvidence() == null ? null : briefing.erpEvidence().exposureScore())
                .addValue("contractGapScore", score(chain == null ? null : chain.contractRag()))
                .addValue("contractClauseCount",
                        briefing.contractFindings() == null ? 0 : briefing.contractFindings().size())
                .addValue("procurementRiskLevel", briefing.procurementRiskLevel())
                .addValue("procurementRiskScore", briefing.procurementRiskScore())
                .addValue("composite", briefing.composite())
                .addValue("briefingText", briefing.briefing())
                .addValue("riskReasons", toJson(orEmpty(briefing.riskReasons())))
                .addValue("recommendedActions", toJson(orEmpty(briefing.recommendedActions())))
                .addValue("erpEvidence", toJson(briefing.erpEvidence()))
                .addValue("contractFindings", toJson(orEmpty(briefing.contractFindings())))
                .addValue("warnings",
                        toJson(verification == null ? List.of() : orEmpty(verification.warnings())))
                .addValue("reviewPassed", verification == null ? null : verification.reviewPassed())
                .addValue("llmUsed", verification != null && verification.llmUsed())
                .addValue("llmError", verification == null ? null : verification.llmError())
                .addValue("weightVersion", verification == null ? null : verification.weightVersion())
                .addValue("mock", verification != null && verification.mock())
                .addValue("createdBy", createdBy)
                .addValue("triggerType", briefing.triggerType())
                .addValue("createdAt", briefing.createdAt());

        jdbc.update("""
                INSERT INTO ai_briefings (
                    briefing_id, assessment_id, source_type, source_ref, subject_title,
                    news_id, analysis_id, source_headline, erp_material_id, erp_supplier_id,
                    erp_contract_id, contract_id, material_name, material_category, impact_domain_final,
                    external_signal_level, external_signal_score, erp_exposure_level, erp_exposure_score,
                    contract_gap_score, contract_clause_count, procurement_risk_level,
                    procurement_risk_score, composite, briefing_text, risk_reasons,
                    recommended_actions, erp_evidence, contract_findings, warnings,
                    review_passed, llm_used, llm_error, weight_version, mock, created_by,
                    trigger_type, created_at
                ) VALUES (
                    :briefingId, :assessmentId, :sourceType, :sourceRef, :subjectTitle,
                    :newsId, :analysisId, :sourceHeadline, :erpMaterialId, :erpSupplierId,
                    :erpContractId, :contractId, :materialName, :materialCategory, :impactDomainFinal,
                    :externalSignalLevel, :externalSignalScore, :erpExposureLevel, :erpExposureScore,
                    :contractGapScore, :contractClauseCount, :procurementRiskLevel,
                    :procurementRiskScore, :composite, :briefingText, CAST(:riskReasons AS JSONB),
                    CAST(:recommendedActions AS JSONB), CAST(:erpEvidence AS JSONB),
                    CAST(:contractFindings AS JSONB), CAST(:warnings AS JSONB),
                    :reviewPassed, :llmUsed, :llmError, :weightVersion, :mock, :createdBy,
                    :triggerType, :createdAt
                )
                """, params);
    }

    /**
     * 같은 뉴스로 만든 이전 브리핑을 지운다 — 목록에는 <b>가장 최근 것 한 건</b>만 남는다.
     *
     * <p>같은 뉴스를 자동 경로(Chain A&rarr;B)가 한 번 태우고 사용자가 AI 브리핑 화면에서 다시
     * 생성하면 같은 사건이 목록에 두 줄로 쌓인다. 둘은 대안이 아니라 <b>같은 것의 옛 판·새 판</b>이라
     * 최신만 보이는 게 맞다(사용자 확인, 2026-08-03).
     *
     * <p>키를 {@code analysis_id}로 잡는 이유: 자동 경로와 화면 경로는 {@code source_ref}가
     * 서로 다르다(화면은 raw_events.id, 자동 경로는 분석 UUID). 두 경로가 공통으로 갖는
     * 식별자는 이것뿐이다.
     *
     * <p>{@code source_type='NEWS'}로 좁히는 게 핵심이다. 자재·계약 화면에서 만든 브리핑도
     * 외부신호로 같은 뉴스를 끌어다 쓰므로 {@code analysis_id}가 겹칠 수 있는데, 그것들은
     * <b>분석 대상이 다른</b> 별개의 브리핑이라 지우면 안 된다.
     */
    private void replaceSameNewsBriefing(AiBriefingDto.BriefingDetail briefing) {
        if (!"NEWS".equals(briefing.sourceType()) || briefing.analysisId() == null) {
            return;
        }
        int removed = jdbc.update("""
                DELETE FROM ai_briefings
                WHERE source_type = 'NEWS'
                  AND analysis_id = :analysisId
                """, new MapSqlParameterSource("analysisId", briefing.analysisId()));
        if (removed > 0) {
            log.info("같은 뉴스의 이전 AI 브리핑 {}건을 새 브리핑으로 대체합니다. analysisId={}",
                    removed, briefing.analysisId());
        }
    }

    /**
     * 이 대상으로 이미 저장돼 있는 가장 최근 브리핑 id. 화면이 프리필과 함께 본문을 바로
     * 띄우는 데 쓴다.
     *
     * <p>{@code source_ref}가 아니라 {@code analysis_id}도 함께 보는 이유: 같은 뉴스라도
     * 자동 경로는 {@code source_ref}에 분석 UUID를, 화면 경로는 {@code raw_events.id}를 넣는다.
     * 둘 중 하나만 보면 다른 경로가 만든 브리핑을 놓친다.
     */
    public Optional<UUID> findLatestBriefingId(String sourceType, String sourceRef, UUID analysisId) {
        List<UUID> found = jdbc.query("""
                SELECT briefing_id
                FROM ai_briefings
                WHERE source_type = :sourceType
                  AND (source_ref = :sourceRef
                       OR (:analysisId IS NOT NULL AND analysis_id = :analysisId))
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource()
                        .addValue("sourceType", sourceType)
                        .addValue("sourceRef", sourceRef)
                        .addValue("analysisId", analysisId),
                (rs, rowNumber) -> rs.getObject("briefing_id", UUID.class));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 분석별 <b>뉴스</b> 브리핑 중 화면에 내보낼 수 있는 최신 1건.
     *
     * <p>{@code source_type='NEWS'}로 좁히는 것이 핵심이다. 자재·계약 화면에서 만든 브리핑도
     * 외부신호로 같은 뉴스를 끌어다 쓰므로 {@code analysis_id}가 겹치는데, 그건 <b>그 계약의
     * 브리핑</b>이지 그 뉴스의 브리핑이 아니다. 좁히지 않으면 뉴스 화면이 남의 브리핑을 자기
     * 것으로 집어 "확정"으로 표시하고, 정작 열어보면 본문이 비어 있다(실측: 계약 브리핑
     * source_ref=5가 뉴스 c6466e73의 브리핑으로 잡혔다).
     *
     * <p>완결 조건 셋을 함께 건다 — 조기 종료된 실행({@code composite=false})은 점수가 0·정상으로
     * 남고, 본문이 없으면 열어도 빈 화면이며, reviewer가 문제를 찾은 건은 확정으로 부를 수 없다.
     * 이 조건을 통과한 브리핑만 화면이 "이 뉴스의 브리핑"으로 삼는다.
     *
     * <p>항목마다 따로 물어보면 N+1이 되므로 배치로 뽑는다
     * ({@code ProcurementRiskRepository.findLatestRiskLevelsByAnalysisIds}와 같은 방침).
     */
    public Map<UUID, NewsBriefingRef> findCompletedNewsBriefingsByAnalysisIds(
            Collection<UUID> analysisIds) {
        if (analysisIds == null || analysisIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, NewsBriefingRef> found = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (analysis_id)
                       analysis_id, briefing_id, procurement_risk_level
                FROM ai_briefings
                WHERE analysis_id IN (:analysisIds)
                  AND source_type = 'NEWS'
                  AND composite = TRUE
                  AND briefing_text IS NOT NULL
                  AND review_passed = TRUE
                ORDER BY analysis_id, created_at DESC
                """,
                new MapSqlParameterSource("analysisIds", analysisIds),
                (rs, rowNumber) -> Map.entry(
                        rs.getObject("analysis_id", UUID.class),
                        new NewsBriefingRef(
                                rs.getObject("briefing_id", UUID.class),
                                rs.getString("procurement_risk_level"))))
                .forEach(entry -> found.put(entry.getKey(), entry.getValue()));
        return found;
    }

    /**
     * 화면에 내보낼 수 있는 뉴스 브리핑 한 건의 요약.
     *
     * <p>등급과 브리핑 id를 <b>같은 행에서</b> 함께 꺼내는 게 목적이다. 따로 조회하면 등급은
     * 계약 브리핑에서, id는 뉴스 브리핑에서 오는 식으로 다시 어긋난다.
     */
    public record NewsBriefingRef(UUID briefingId, String procurementRiskLevel) {}

    public Optional<AiBriefingDto.BriefingDetail> findById(UUID briefingId) {
        List<AiBriefingDto.BriefingDetail> rows = jdbc.query("""
                SELECT *
                FROM ai_briefings
                WHERE briefing_id = :briefingId
                """, new MapSqlParameterSource("briefingId", briefingId),
                (rs, rowNumber) -> mapDetail(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * "최근 브리핑" 필터 조건. 전부 null이면 전체다.
     *
     * <p>네 축 모두 SQL로 내려간다. 앱에서 거르면 "상한만큼 먼저 자르고 그 안에서 필터"가 되어,
     * 뒷 페이지에 있어야 할 항목이 앞에서 걸러져 통째로 사라진다 — 리스크 모니터링 목록이 정확히
     * 그래서 16건 중 4건만 보였다(2026-08-03 수정).
     */
    public record BriefingListFilter(
            /** {@code NEWS}/{@code MATERIAL}/{@code CONTRACT}. */
            String sourceType,
            /** {@code CRITICAL}/{@code WARNING}/{@code NORMAL}. */
            String riskLevel,
            /** {@code PASSED}(검증 통과)/{@code FAILED}(검토 필요)/{@code PENDING}(미검증). */
            String reviewStatus,
            /** 최근 N일. null이면 기간 제한 없음. */
            Integer days) {

        static final BriefingListFilter ALL = new BriefingListFilter(null, null, null, null);
    }

    /**
     * 목록 조회 공통 조건. 건수 세기와 페이지 조회가 <b>같은 문자열</b>을 쓴다 — 두 벌로 두면
     * 한쪽만 고쳐졌을 때 "전체 17건인데 3페이지를 넘겨도 5건뿐"처럼 총계와 내용이 어긋난다.
     *
     * <p>등급 필터에 {@code composite = TRUE}를 함께 거는 이유: 조기 종료된 실행도
     * {@code procurement_risk_level}에 값이 남아 있지만(0점·NORMAL) 화면은 그걸 등급으로 읽지
     * 않고 "평가 미완료"로 표시한다. 이 조건이 없으면 "정상"으로 걸렀는데 목록에는 "평가 미완료"
     * 카드가 나오는, 화면이 설명할 수 없는 결과가 된다.
     *
     * <p>검증상태 셋은 서로 배타적이고 {@code review_passed}의 세 가지 상태(TRUE·FALSE·NULL)를
     * 빠짐없이 덮는다. NULL을 "미검증"으로 따로 두지 않으면 reviewer를 아직 안 거친 브리핑을
     * 찾을 방법이 없다.
     */
    private static final String LIST_CONDITIONS = """
            WHERE (CAST(:sourceType AS VARCHAR) IS NULL
                   OR source_type = CAST(:sourceType AS VARCHAR))
              AND (CAST(:riskLevel AS VARCHAR) IS NULL
                   OR (composite = TRUE
                       AND procurement_risk_level = CAST(:riskLevel AS VARCHAR)))
              AND (CAST(:reviewStatus AS VARCHAR) IS NULL
                   OR (CAST(:reviewStatus AS VARCHAR) = 'PASSED'  AND review_passed = TRUE)
                   OR (CAST(:reviewStatus AS VARCHAR) = 'FAILED'  AND review_passed = FALSE)
                   OR (CAST(:reviewStatus AS VARCHAR) = 'PENDING' AND review_passed IS NULL))
              AND (CAST(:days AS INTEGER) IS NULL
                   OR created_at >= NOW() - make_interval(days => CAST(:days AS INTEGER)))
            """;

    /**
     * "최근 브리핑" 한 페이지. 팀 전체 공용이라 {@code created_by}로 거르지 않는다.
     *
     * <p>본문·근거 JSONB를 열지 않는다 — 카드에 필요한 건 제목·등급·검증 결과·시각뿐인데
     * 전체를 읽으면 목록 N건마다 JSONB 5개를 헛되이 파싱한다
     * ({@code ProcurementRiskRepository.findLatestRiskLevelsByAnalysisIds}와 같은 방침).
     */
    public List<AiBriefingDto.BriefingListItem> findPage(
            BriefingListFilter filter, int page, int size) {
        return jdbc.query("""
                SELECT briefing_id, source_type, source_ref, subject_title, news_id,
                       procurement_risk_level, procurement_risk_score, composite,
                       review_passed, trigger_type, created_at
                FROM ai_briefings
                """ + LIST_CONDITIONS + """
                ORDER BY created_at DESC
                LIMIT :size OFFSET :offset
                """,
                listParams(filter)
                        .addValue("size", size)
                        .addValue("offset", (long) page * size),
                (rs, rowNumber) -> new AiBriefingDto.BriefingListItem(
                        rs.getObject("briefing_id", UUID.class),
                        rs.getString("source_type"),
                        rs.getString("source_ref"),
                        rs.getString("subject_title"),
                        rs.getString("news_id"),
                        rs.getString("procurement_risk_level"),
                        rs.getBigDecimal("procurement_risk_score"),
                        rs.getBoolean("composite"),
                        nullableBoolean(rs, "review_passed"),
                        rs.getString("trigger_type"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    /** 같은 조건의 전체 건수. 화면이 마지막 페이지에서 "다음"을 잠그는 데 쓴다. */
    public long countPage(BriefingListFilter filter) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_briefings\n" + LIST_CONDITIONS,
                listParams(filter), Long.class);
        return count == null ? 0L : count;
    }

    private static MapSqlParameterSource listParams(BriefingListFilter filter) {
        BriefingListFilter applied = filter == null ? BriefingListFilter.ALL : filter;
        return new MapSqlParameterSource()
                .addValue("sourceType", applied.sourceType(), Types.VARCHAR)
                .addValue("riskLevel", applied.riskLevel(), Types.VARCHAR)
                .addValue("reviewStatus", applied.reviewStatus(), Types.VARCHAR)
                .addValue("days", applied.days(), Types.INTEGER);
    }

    /**
     * 같은 대상·같은 분석으로 <b>방금</b> 만든 브리핑. 재시도로 인한 중복 실행을 막는 데 쓴다.
     *
     * <p>완전한 멱등성 키가 아니라 <b>짧은 시간창</b>이다. 막으려는 것은 "저장은 됐는데 응답이
     * 유실돼 사용자가 다시 누르는" 경우와 그때 다시 나가는 LLM 비용이며, 한참 뒤에 같은 대상을
     * 다시 평가하는 것은 정상적인 요구라 막지 않는다.
     *
     * <p>{@code created_at}은 실행 <b>시작</b> 시각({@code asOf})이므로 시간창은 시작 기준으로
     * 비교된다 — 실행에 걸린 시간만큼 창이 짧아지는 셈이라 안전한 쪽이다.
     */
    public Optional<AiBriefingDto.BriefingDetail> findRecentDuplicate(
            String sourceType, String sourceRef, UUID analysisId, OffsetDateTime since) {
        List<AiBriefingDto.BriefingDetail> rows = jdbc.query("""
                SELECT *
                FROM ai_briefings
                WHERE source_type = :sourceType
                  AND source_ref = :sourceRef
                  AND analysis_id IS NOT DISTINCT FROM :analysisId
                  AND created_at >= :since
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("sourceType", sourceType)
                        .addValue("sourceRef", sourceRef)
                        .addValue("analysisId", analysisId)
                        .addValue("since", since),
                (rs, rowNumber) -> mapDetail(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 저장된 행을 화면 응답으로 되돌린다.
     *
     * <p>"분석 근거" 4칸은 저장하지 않고 컬럼에서 다시 조립한다 — 4칸은 전부 다른 컬럼의 표현일
     * 뿐이라, 따로 저장하면 같은 값이 두 벌 남고 나중에 서로 어긋난다.
     */
    private AiBriefingDto.BriefingDetail mapDetail(ResultSet rs) throws SQLException {
        List<Map<String, Object>> findings = fromJson(rs.getString("contract_findings"), OBJECT_LIST);
        int clauseCount = rs.getInt("contract_clause_count");

        return new AiBriefingDto.BriefingDetail(
                rs.getObject("briefing_id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("subject_title"),
                rs.getString("news_id"),
                rs.getObject("analysis_id", UUID.class),
                rs.getString("source_headline"),
                rs.getString("erp_material_id"),
                rs.getString("erp_supplier_id"),
                rs.getString("erp_contract_id"),
                nullableLong(rs, "contract_id"),
                rs.getString("material_name"),
                rs.getString("material_category"),
                rs.getString("impact_domain_final"),
                rs.getBoolean("composite"),
                rs.getString("procurement_risk_level"),
                rs.getBigDecimal("procurement_risk_score"),
                rs.getString("briefing_text"),
                fromJson(rs.getString("risk_reasons"), STRING_LIST),
                fromJson(rs.getString("recommended_actions"), STRING_LIST),
                fromJson(rs.getString("erp_evidence"), ERP_EVIDENCE),
                findings,
                new AiBriefingDto.EvidenceChain(
                        new AiBriefingDto.Step("외부 이벤트",
                                rs.getString("external_signal_level"),
                                rs.getBigDecimal("external_signal_score"), null),
                        new AiBriefingDto.Step("ERP 노출",
                                rs.getString("erp_exposure_level"),
                                rs.getBigDecimal("erp_exposure_score"), null),
                        new AiBriefingDto.Step("계약 RAG", null,
                                rs.getBigDecimal("contract_gap_score"), clauseCount + "개 조항"),
                        new AiBriefingDto.Step("최종 위험",
                                rs.getString("procurement_risk_level"),
                                rs.getBigDecimal("procurement_risk_score"), null)),
                new AiBriefingDto.VerificationMeta(
                        nullableBoolean(rs, "review_passed"),
                        rs.getBoolean("llm_used"),
                        rs.getString("llm_error"),
                        warningCount(rs.getString("warnings")),
                        fromJson(rs.getString("warnings"), STRING_LIST),
                        firstLong(findings, "contract_id"),
                        firstInteger(findings, "page"),
                        rs.getString("weight_version"),
                        rs.getBoolean("mock")),
                rs.getString("trigger_type"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private int warningCount(String warningsJson) {
        List<String> warnings = fromJson(warningsJson, STRING_LIST);
        return warnings == null ? 0 : warnings.size();
    }

    private static Long firstLong(List<Map<String, Object>> findings, String key) {
        Object value = firstValue(findings, key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer firstInteger(List<Map<String, Object>> findings, String key) {
        Object value = firstValue(findings, key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object firstValue(List<Map<String, Object>> findings, String key) {
        return findings == null || findings.isEmpty() ? null : findings.get(0).get(key);
    }

    private static String level(AiBriefingDto.Step step) {
        return step == null ? null : step.level();
    }

    private static BigDecimal score(AiBriefingDto.Step step) {
        return step == null ? null : step.score();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 브리핑 JSON 직렬화에 실패했습니다.", exception);
        }
    }

    /**
     * F10 메일 본문 enrichment용 — 평가에 연결된 <b>최신</b> 브리핑의 본문·권장조치만 가져온다.
     * 전체 {@link AiBriefingDto.BriefingDetail}(JSONB 여러 개)을 열지 않고 두 컬럼만 읽는다.
     * 일일 다이제스트는 이미 저장된 브리핑을 여기서 끌어오고, 즉시 CRITICAL 메일은 저장 이전이라
     * {@code response} 값을 직접 쓰므로 이걸 호출하지 않는다.
     */
    public Optional<BriefingSummary> findLatestSummaryByAssessmentId(UUID assessmentId) {
        if (assessmentId == null) {
            return Optional.empty();
        }
        List<BriefingSummary> rows = jdbc.query("""
                SELECT briefing_text, recommended_actions
                FROM ai_briefings
                WHERE assessment_id = :assessmentId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, n) -> new BriefingSummary(
                        rs.getString("briefing_text"),
                        fromJson(rs.getString("recommended_actions"), STRING_LIST)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 메일 본문용 브리핑 요약 — 본문과 권장조치만. */
    public record BriefingSummary(String briefingText, List<String> recommendedActions) {}

    private <T> T fromJson(String value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 브리핑 JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}

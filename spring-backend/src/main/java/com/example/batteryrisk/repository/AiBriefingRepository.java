package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.AiBriefingDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
                    review_passed, llm_used, llm_error, weight_version, mock, created_by, created_at
                ) VALUES (
                    :briefingId, :assessmentId, :sourceType, :sourceRef, :subjectTitle,
                    :newsId, :analysisId, :sourceHeadline, :erpMaterialId, :erpSupplierId,
                    :erpContractId, :contractId, :materialName, :materialCategory, :impactDomainFinal,
                    :externalSignalLevel, :externalSignalScore, :erpExposureLevel, :erpExposureScore,
                    :contractGapScore, :contractClauseCount, :procurementRiskLevel,
                    :procurementRiskScore, :composite, :briefingText, CAST(:riskReasons AS JSONB),
                    CAST(:recommendedActions AS JSONB), CAST(:erpEvidence AS JSONB),
                    CAST(:contractFindings AS JSONB), CAST(:warnings AS JSONB),
                    :reviewPassed, :llmUsed, :llmError, :weightVersion, :mock, :createdBy, :createdAt
                )
                """, params);
    }

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
     * "최근 브리핑" 목록. 팀 전체 공용이라 {@code created_by}로 거르지 않는다.
     *
     * <p>본문·근거 JSONB를 열지 않는다 — 카드에 필요한 건 제목·등급·검증 결과·시각뿐인데
     * 전체를 읽으면 목록 N건마다 JSONB 5개를 헛되이 파싱한다
     * ({@code ProcurementRiskRepository.findLatestRiskLevelsByAnalysisIds}와 같은 방침).
     */
    public List<AiBriefingDto.BriefingListItem> findRecent(int limit) {
        return jdbc.query("""
                SELECT briefing_id, source_type, subject_title, news_id,
                       procurement_risk_level, procurement_risk_score, composite,
                       review_passed, created_at
                FROM ai_briefings
                ORDER BY created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit),
                (rs, rowNumber) -> new AiBriefingDto.BriefingListItem(
                        rs.getObject("briefing_id", UUID.class),
                        rs.getString("source_type"),
                        rs.getString("subject_title"),
                        rs.getString("news_id"),
                        rs.getString("procurement_risk_level"),
                        rs.getBigDecimal("procurement_risk_score"),
                        rs.getBoolean("composite"),
                        nullableBoolean(rs, "review_passed"),
                        rs.getObject("created_at", OffsetDateTime.class)));
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

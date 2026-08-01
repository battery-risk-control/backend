package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.ContractRagDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 계약 · RAG 화면이 쓰는 조회 전용 Repository.
 *
 * <p>{@link ErpRepository}와 같은 JdbcTemplate 방식이다 — 계약·공급사·자재·문서를 한 번에 조인해
 * 화면 한 장을 채우는 쿼리라 엔티티 그래프보다 SQL이 읽기 쉽다. 쓰기는 하지 않는다(적재는
 * {@code DocumentService}가 담당).
 */
@Repository
public class ContractRagRepository {
    /** 계약 요약에 공통으로 쓰는 조인. 문서 수·청크 수는 COMPLETED 문서만 센다. */
    private static final String CONTRACT_SELECT = """
            SELECT c.contract_id, c.erp_contract_id, c.contract_name, c.status,
                   c.start_date, c.end_date, c.currency_code,
                   s.supplier_id, s.erp_supplier_id, s.supplier_name, s.country_code,
                   m.material_id, m.erp_material_id, m.material_name, m.material_category,
                   COALESCE(d.document_count, 0) AS document_count,
                   COALESCE(d.chunk_count, 0)    AS indexed_chunk_count
              FROM contracts c
              LEFT JOIN suppliers s ON s.supplier_id = c.supplier_id
              LEFT JOIN materials m ON m.material_id = c.material_id
              LEFT JOIN (
                    SELECT contract_id,
                           COUNT(*)                AS document_count,
                           SUM(chunk_count)        AS chunk_count
                      FROM contract_documents
                     WHERE processing_status = 'COMPLETED'
                     GROUP BY contract_id
              ) d ON d.contract_id = c.contract_id
            """;

    private final JdbcTemplate jdbc;

    public ContractRagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 적재된 문서가 있는 계약을 먼저, 그다음 ERP 계약번호 순으로 반환한다. */
    public List<ContractRagDto.ContractSummary> findContracts(boolean indexedOnly) {
        String sql = CONTRACT_SELECT
                + (indexedOnly ? " WHERE COALESCE(d.document_count, 0) > 0 " : "")
                + " ORDER BY COALESCE(d.document_count, 0) DESC, c.erp_contract_id NULLS LAST, c.contract_id";
        return jdbc.query(sql, CONTRACT_MAPPER);
    }

    public Optional<ContractRagDto.ContractSummary> findContract(long contractId) {
        List<ContractRagDto.ContractSummary> rows = jdbc.query(
                CONTRACT_SELECT + " WHERE c.contract_id = ?", CONTRACT_MAPPER, contractId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 검색 결과에 계약 메타를 붙이기 위한 일괄 조회. 검색 1회당 쿼리 1번으로 끝낸다. */
    public Map<Long, ContractRagDto.ContractSummary> findContractsByIds(Collection<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", contractIds.stream().map(id -> "?").toList());
        List<ContractRagDto.ContractSummary> rows = jdbc.query(
                CONTRACT_SELECT + " WHERE c.contract_id IN (" + placeholders + ")",
                CONTRACT_MAPPER,
                contractIds.toArray());
        Map<Long, ContractRagDto.ContractSummary> byId = new LinkedHashMap<>();
        for (ContractRagDto.ContractSummary row : rows) {
            byId.put(row.contractId(), row);
        }
        return byId;
    }

    public List<ContractRagDto.DocumentItem> findDocuments(long contractId) {
        return jdbc.query("""
                SELECT document_id, original_file_name, document_type, mime_type, file_size_bytes,
                       processing_status, chunk_count, embedding_type, embedding_version,
                       error_code, error_message, created_at, processed_at
                  FROM contract_documents
                 WHERE contract_id = ?
                 ORDER BY created_at DESC
                """, DOCUMENT_MAPPER, contractId);
    }

    /**
     * 이 계약과 관련된, DB에 저장돼 있는 <b>가장 최신</b> 뉴스 분석 한 건.
     *
     * <p>"관련"의 기준은 자재 대분류({@code material_category})다. 같은 대분류 안에서는
     * 공급사 국적과 일치하는 기사를 먼저 고른다 — 코발트 계약이 콩고 공급사면 콩고발 코발트
     * 기사가 그 계약에 훨씬 직접적이기 때문이다. 없으면 대분류만 맞는 최신 기사로 내려간다.
     *
     * <p>멀티에이전트를 태울 수 있는 분석만 고른다(완료 + 외부신호 점수 존재 + 국가 특정 +
     * 공급망 무관 판정 아님) — 조건은 {@code RiskMonitoringService.erpImpactBlockedReason}과 같다.
     */
    public Optional<ContractRagDto.SourceNews> findLatestRelatedNews(
            String materialCategory, String supplierCountryCode) {
        if (materialCategory == null || materialCategory.isBlank()) {
            return Optional.empty();
        }
        List<ContractRagDto.SourceNews> rows = jdbc.query("""
                SELECT a.analysis_id, a.event_title, a.summary_kr, a.country_code,
                       a.material_category, a.impact_domain, a.severity, a.severity_score,
                       a.completed_at,
                       e.id AS event_id, e.title_ko, e.collected_at,
                       COALESCE(e.source_url, a.source_url) AS source_url
                  FROM analyses a
                  LEFT JOIN raw_events e ON e.triggered_analysis_id = a.analysis_id
                 WHERE a.status = 'COMPLETED'
                   AND a.material_category = ?
                   AND a.severity IS NOT NULL
                   AND a.severity_score IS NOT NULL
                   AND a.country_code IS NOT NULL
                   AND a.country_code <> ''
                   AND a.impact_domain IS NOT NULL
                   AND UPPER(a.impact_domain) <> 'IRRELEVANT'
                   AND (a.reason_codes IS NULL OR a.reason_codes NOT LIKE '%NOT_RELEVANT%')
                 ORDER BY (a.country_code = ?) DESC, a.completed_at DESC NULLS LAST
                 LIMIT 1
                """, NEWS_MAPPER, materialCategory, supplierCountryCode == null ? "" : supplierCountryCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<ContractRagDto.ContractSummary> CONTRACT_MAPPER = (rs, rowNum) ->
            new ContractRagDto.ContractSummary(
                    rs.getLong("contract_id"),
                    rs.getString("erp_contract_id"),
                    rs.getString("contract_name"),
                    rs.getString("status"),
                    localDate(rs, "start_date"),
                    localDate(rs, "end_date"),
                    rs.getString("currency_code"),
                    nullableLong(rs, "supplier_id"),
                    rs.getString("erp_supplier_id"),
                    rs.getString("supplier_name"),
                    rs.getString("country_code"),
                    nullableLong(rs, "material_id"),
                    rs.getString("erp_material_id"),
                    rs.getString("material_name"),
                    rs.getString("material_category"),
                    rs.getInt("document_count"),
                    rs.getInt("indexed_chunk_count"));

    private static final RowMapper<ContractRagDto.DocumentItem> DOCUMENT_MAPPER = (rs, rowNum) ->
            new ContractRagDto.DocumentItem(
                    rs.getString("document_id"),
                    rs.getString("original_file_name"),
                    rs.getString("document_type"),
                    rs.getString("mime_type"),
                    rs.getLong("file_size_bytes"),
                    rs.getString("processing_status"),
                    rs.getInt("chunk_count"),
                    rs.getString("embedding_type"),
                    rs.getString("embedding_version"),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    instant(rs, "created_at"),
                    instant(rs, "processed_at"));

    private static final RowMapper<ContractRagDto.SourceNews> NEWS_MAPPER = (rs, rowNum) -> {
        Double severityScore = (Double) rs.getObject("severity_score");
        return new ContractRagDto.SourceNews(
                (UUID) rs.getObject("analysis_id"),
                nullableLong(rs, "event_id"),
                rs.getString("event_title"),
                rs.getString("title_ko"),
                rs.getString("summary_kr"),
                rs.getString("country_code"),
                rs.getString("material_category"),
                rs.getString("impact_domain"),
                rs.getString("severity"),
                severityScore == null ? null : (int) Math.round(severityScore),
                rs.getString("source_url"),
                instant(rs, "collected_at"),
                instant(rs, "completed_at"));
    };

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

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

    /**
     * 적재된 문서가 있는 계약을 먼저, 그다음 ERP 계약번호 순으로 반환한다.
     *
     * <p><b>ERP 계약번호가 없는 항목은 제외한다.</b> 실제 ERP 계약은 모두 CTR-XXX 번호를 갖고,
     * 번호가 없는 건 C1 문서-업로드 기능용 참조 시드 픽스처(R__insert_c1_reference_seed.sql)뿐이다 —
     * 실제 계약이 아니고 문서도 없어 검색에 걸릴 수 없으므로, RAG 선택 목록에 노출하지 않는다.
     */
    public List<ContractRagDto.ContractSummary> findContracts(boolean indexedOnly) {
        String sql = CONTRACT_SELECT
                + " WHERE c.erp_contract_id IS NOT NULL AND c.erp_contract_id <> '' "
                + (indexedOnly ? " AND COALESCE(d.document_count, 0) > 0 " : "")
                + " ORDER BY COALESCE(d.document_count, 0) DESC, c.erp_contract_id NULLS LAST, c.contract_id";
        return jdbc.query(sql, CONTRACT_MAPPER);
    }

    public Optional<ContractRagDto.ContractSummary> findContract(long contractId) {
        List<ContractRagDto.ContractSummary> rows = jdbc.query(
                CONTRACT_SELECT + " WHERE c.contract_id = ?", CONTRACT_MAPPER, contractId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 적재된 문서가 있는 아웃바운드 계약을 먼저, 그다음 CTR-OUT 번호 순으로 반환한다. */
    public List<ContractRagDto.ContractSummary> findOutboundContracts(boolean indexedOnly) {
        String sql = OUTBOUND_CONTRACT_SELECT
                + (indexedOnly ? " WHERE COALESCE(d.document_count, 0) > 0 " : "")
                + " ORDER BY COALESCE(d.document_count, 0) DESC, oc.erp_outbound_contract_id";
        return jdbc.query(sql, OUTBOUND_CONTRACT_MAPPER);
    }

    /** 아웃바운드 계약 1건 요약(우측 문서 패널 상세용). */
    public Optional<ContractRagDto.ContractSummary> findOutboundContract(long outboundContractId) {
        List<ContractRagDto.ContractSummary> rows = jdbc.query(
                OUTBOUND_CONTRACT_SELECT + " WHERE oc.outbound_contract_id = ?",
                OUTBOUND_CONTRACT_MAPPER, outboundContractId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 아웃바운드 계약에 달린 문서 목록. 컬럼이 인바운드와 같아 DOCUMENT_MAPPER를 재사용한다. */
    public List<ContractRagDto.DocumentItem> findOutboundDocuments(long outboundContractId) {
        return jdbc.query("""
                SELECT document_id, original_file_name, document_type, mime_type, file_size_bytes,
                       processing_status, chunk_count, embedding_type, embedding_version,
                       error_code, error_message, created_at, processed_at
                  FROM outbound_contract_documents
                 WHERE outbound_contract_id = ?
                 ORDER BY created_at DESC
                """, DOCUMENT_MAPPER, outboundContractId);
    }

    /** 아웃바운드 검색 결과에 계약 메타를 붙이기 위한 일괄 조회(outbound_contract_id 기준). */
    public Map<Long, ContractRagDto.ContractSummary> findOutboundContractsByIds(Collection<Long> outboundContractIds) {
        if (outboundContractIds == null || outboundContractIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", outboundContractIds.stream().map(id -> "?").toList());
        List<ContractRagDto.ContractSummary> rows = jdbc.query(
                OUTBOUND_CONTRACT_SELECT + " WHERE oc.outbound_contract_id IN (" + placeholders + ")",
                OUTBOUND_CONTRACT_MAPPER,
                outboundContractIds.toArray());
        Map<Long, ContractRagDto.ContractSummary> byId = new LinkedHashMap<>();
        for (ContractRagDto.ContractSummary row : rows) {
            byId.put(row.contractId(), row);
        }
        return byId;
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
     * 이 계약과 <b>가장 관련 깊은</b> 뉴스 분석 한 건.
     *
     * <p><b>"가장 최신"이 아니다.</b> 1순위가 공급사 국적 일치이고, 최신순은 그 안에서의
     * 동점 처리 기준이다. 콩고 공급사 계약에 칠레 리튬 기사를 붙이면 뒤이어 도는 ERP 노출도
     * 계산이 엉뚱한 공급망을 보게 되므로, 하루 늦은 콩고 기사가 오늘 나온 칠레 기사보다 낫다.
     * 절대 최신이 필요해지면 {@code ORDER BY}의 두 항을 맞바꾸면 되지만, 그건 정책 변경이다.
     *
     * <p>"관련"의 1차 기준은 자재 대분류({@code material_category})이고, 같은 대분류 안에서
     * 공급사 국적과 일치하는 기사를 먼저 고른다. 없으면 대분류만 맞는 최신 기사로 내려간다.
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
                    rs.getInt("indexed_chunk_count"),
                    "INBOUND",
                    null, null, null, null, null, null);

    /**
     * 아웃바운드(제품 납품) 계약 요약. outbound_contracts에는 contract_name·기간·통화 개념이
     * 없어(대신 물량·단가·위약금) 화면 표시용 이름을 제품·고객사로 합성한다. 문서 수·청크 수는
     * COMPLETED 문서만 센다(인바운드와 동일).
     */
    private static final String OUTBOUND_CONTRACT_SELECT = """
            SELECT oc.outbound_contract_id, oc.erp_outbound_contract_id,
                   oc.product_id, p.erp_product_id, COALESCE(p.name_kr, p.name_en) AS product_name,
                   oc.customer_id, c.erp_customer_id, COALESCE(c.name_kr, c.name_en) AS customer_name,
                   oc.contract_language,
                   COALESCE(d.document_count, 0) AS document_count,
                   COALESCE(d.chunk_count, 0)    AS indexed_chunk_count
              FROM outbound_contracts oc
              LEFT JOIN products p  ON p.product_id = oc.product_id
              LEFT JOIN customers c ON c.customer_id = oc.customer_id
              LEFT JOIN (
                    SELECT outbound_contract_id,
                           COUNT(*)         AS document_count,
                           SUM(chunk_count) AS chunk_count
                      FROM outbound_contract_documents
                     WHERE processing_status = 'COMPLETED'
                     GROUP BY outbound_contract_id
              ) d ON d.outbound_contract_id = oc.outbound_contract_id
            """;

    private static final RowMapper<ContractRagDto.ContractSummary> OUTBOUND_CONTRACT_MAPPER = (rs, rowNum) -> {
        String productName = rs.getString("product_name");
        String customerName = rs.getString("customer_name");
        // "4680 원통형 → Tesla" 형태. 이름을 못 찾으면 있는 쪽만, 둘 다 없으면 계약번호로 대체한다.
        String display;
        if (productName != null && customerName != null) {
            display = productName + " → " + customerName;
        } else if (productName != null || customerName != null) {
            display = productName != null ? productName : customerName;
        } else {
            display = rs.getString("erp_outbound_contract_id");
        }
        return new ContractRagDto.ContractSummary(
                rs.getLong("outbound_contract_id"),
                rs.getString("erp_outbound_contract_id"),
                display,
                "ACTIVE",
                null, null,
                "USD",
                null, null, null, null,
                null, null, null, null,
                rs.getInt("document_count"),
                rs.getInt("indexed_chunk_count"),
                "OUTBOUND",
                nullableLong(rs, "product_id"),
                rs.getString("erp_product_id"),
                productName,
                nullableLong(rs, "customer_id"),
                rs.getString("erp_customer_id"),
                customerName);
    };

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

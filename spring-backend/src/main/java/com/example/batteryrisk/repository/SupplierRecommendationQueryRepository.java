package com.example.batteryrisk.repository;

import com.example.batteryrisk.dto.DashboardDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 대시보드용 대체 공급사 추천 조회.
 *
 * <p>{@link AnalysisSupplierRecommendationRepository}(JPA)와 달리 {@code suppliers}를 조인해
 * <b>현재</b> 승인 상태·위험도를 함께 가져온다. 추천 자체는 과거 분석 시점의 결과지만 "지금
 * 발주할 수 있는가"는 지금 기준이어야 하므로, 저장 시점 값을 그대로 보여주면 이미 거래중지된
 * 공급사를 추천으로 띄우게 된다.
 */
@Repository
public class SupplierRecommendationQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SupplierRecommendationQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 가장 최근 분석의 대체 후보를 rank 순으로 {@code limit}건.
     *
     * <p>여러 분석의 추천을 섞지 않는다 — 분석마다 대상 자재가 달라 섞으면 "리튬 후보와 니켈
     * 후보"가 한 목록에 나란히 서게 된다. 가장 최근 분석 하나만 본다.
     */
    public List<DashboardDto.AlternativeSupplier> findLatestAlternatives(int limit) {
        return jdbc.query("""
                WITH latest AS (
                    SELECT analysis_id
                    FROM analysis_supplier_recommendations
                    ORDER BY created_at DESC
                    LIMIT 1
                )
                SELECT r.rank_position, r.supplier_code, r.supplier_name,
                       r.recommendation_reason, r.pros, r.cons,
                       s.supplier_status, s.risk_level
                FROM analysis_supplier_recommendations r
                JOIN latest ON latest.analysis_id = r.analysis_id
                LEFT JOIN suppliers s ON s.supplier_id = r.supplier_id
                ORDER BY r.rank_position
                LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("limit", limit),
                (rs, rowNumber) -> new DashboardDto.AlternativeSupplier(
                        rs.getInt("rank_position"),
                        rs.getString("supplier_code"),
                        rs.getString("supplier_name"),
                        rs.getString("supplier_status"),
                        rs.getString("risk_level"),
                        rs.getString("recommendation_reason"),
                        rs.getString("pros"),
                        rs.getString("cons")));
    }
}

package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 14단계 조회·집계 API DTO를 한 파일에 모읍니다. */
public final class DashboardDto {
    private DashboardDto() {}

    /**
     * 대시보드 상단 요약.
     *
     * <p>등급별 건수는 <b>자재별 최신 Severity 1건</b>을 기준으로 집계한다.
     * 같은 자재를 여러 번 분석해도 현재 상태는 하나이므로, 누적 이력이 아니라 현재 상태를 센다.
     */
    public record Summary(
            long assessedMaterialCount,
            long criticalCount,
            long warningCount,
            long normalCount,
            long unknownCount,
            long briefingCount,
            long materialCount,
            long supplierCount,
            long contractCount,
            long documentCount,
            OffsetDateTime latestAssessedAt,
            boolean mock
    ) {}

    /**
     * 구매 리스크 KPI 요약(멀티에이전트).
     *
     * <p>자재 대분류(8종) 단위로 최신 {@code procurement_risk_assessments} 1건만 남겨 집계한다
     * ({@link com.example.batteryrisk.repository.DashboardRepository}의 {@code material_category}
     * 기준 CTE). {@link Summary}가 {@code severity_assessments} 기반인 것과 달리 이건 Chain B
     * 멀티에이전트 결과 기반이라 별도 record로 둔다.
     */
    public record ProcurementRiskSummary(
            long assessedCategoryCount,
            long criticalCount,
            long warningCount,
            long normalCount,
            BigDecimal erpExposureScoreAvg,
            BigDecimal externalSignalScoreAvg,
            long verifiedBriefingCount,
            OffsetDateTime latestAssessedAt,

            // 최근 24시간 raw 활동량 — "카테고리별 최신 1건" 스냅샷과는 별개 모집단(원본 행
            // 전체, 완료 처리 여부 무관)이라 위 필드들과 섞어 계산하지 않는다.
            //
            // @JsonProperty 명시 필요: 전역 SNAKE_CASE 전략이 문자→숫자 경계(...Count|24h)엔
            // 언더스코어를 안 넣어 "critical_count24h"로 잘못 직렬화됨(Docker 실측 중 발견) —
            // 이 파일의 다른 필드는 전략에 맡기지만 이 4개만 명시로 강제한다.
            @JsonProperty("critical_count_24h") long criticalCount24h,
            @JsonProperty("warning_count_24h") long warningCount24h,
            @JsonProperty("erp_exposure_score_avg_24h") BigDecimal erpExposureScoreAvg24h,
            @JsonProperty("external_signal_score_avg_24h") BigDecimal externalSignalScoreAvg24h,

            boolean mock
    ) {}

    /** 자재별 현재 리스크 — 프론트엔드 리스크 게이지·스코어 카드용. */
    public record MaterialRiskItem(
            String erpMaterialId,
            String materialName,
            String severity,
            BigDecimal score,
            BigDecimal inventoryDays,
            BigDecimal safetyStockDays,
            BigDecimal supplierDependencyRatio,
            String feocStatus,
            String dataQualityStatus,
            OffsetDateTime assessedAt
    ) {}

    /** 특정 자재의 공급사 점유율 분해 — 수입 의존도 도넛 차트용. 색상은 화면에서 정한다. */
    public record ImportDependencyItem(
            String erpSupplierId,
            String supplierName,
            String countryCode,
            BigDecimal supplyShareRatio,
            String approvedStatus,
            boolean isAlternative
    ) {}

    public record ImportDependency(
            String erpMaterialId,
            String materialName,
            BigDecimal totalShareRatio,
            List<ImportDependencyItem> breakdown
    ) {}

    /**
     * 원자재 리스크 요약 한 줄(대시보드 "원자재별 리스크 요약").
     *
     * <p><b>모집단이 {@link ProcurementRiskSummary}와 다르다.</b> KPI는 대분류별 <i>최신</i>
     * 평가 1건을 보지만 이쪽은 대분류별 <i>점수 상위 3건</i>을 본다 — 한 자재에 뉴스가 여러 건
     * 들어왔을 때 가장 최근 것 하나만으로 등급을 정하면, 바로 앞에 들어온 더 심각한 뉴스가
     * 화면에서 사라진다.
     *
     * <p>{@code riskScore}는 상위 3건의 평균, {@code riskLevel}은 그 3건 중 <b>가장 높은</b>
     * 등급이다. 평균 등급을 따로 계산하지 않는 이유는 "평균 45점이라 주의"인데 그 안에 심각
     * 1건이 섞여 있으면 그 심각이 묻히기 때문이다.
     *
     * <p>점수는 {@code procurement_risk_score}, 즉 외부신호·ERP노출·계약공백을 모두 합친
     * <b>최종 합성 점수</b>다({@code MaterialRiskService}의 ERP 노출도 단독 점수가 아니다).
     *
     * <p>평가가 한 건도 없는 자재도 행을 만든다 — 7종을 항상 같은 자리에 보여줘야 "이 자재는
     * 아직 확인 못 했다"가 드러난다. 그 경우 점수·등급·{@code latestAssessmentId}가 전부 null이다.
     */
    public record MaterialRiskSummaryItem(
            String materialCategory,
            String materialName,
            /** 상위 3건 평균(0~100). 평가 0건이면 null. */
            BigDecimal riskScore,
            /** 상위 3건 중 최고 등급(CRITICAL/WARNING/NORMAL). 평가 0건이면 null. */
            String riskLevel,
            /**
             * 24시간 전 시점의 같은 계산값. 그때까지 쌓인 평가가 없으면 null이고, 그러면
             * {@code scoreDelta}도 null이라 화면이 ▲▼를 그리지 않는다.
             *
             * <p>{@code @JsonProperty} 명시 필요: 전역 SNAKE_CASE 전략이 문자→숫자 경계
             * ({@code Score|24h})에 언더스코어를 안 넣어 {@code risk_score24h_ago}로 잘못
             * 직렬화된다({@link ProcurementRiskSummary}의 24h 필드와 같은 문제 — 실측 확인).
             */
            @JsonProperty("risk_score_24h_ago") BigDecimal riskScore24hAgo,
            /** {@code riskScore - riskScore24hAgo}. 한쪽이라도 null이면 null. */
            BigDecimal scoreDelta,
            /**
             * 완료 처리 대상. 이 자재 대분류의 <b>최신</b> 미완료 평가 id다 — KPI 건수가 세는
             * 바로 그 행이라, 이걸 완료 처리해야 KPI에서 빠진다(상위 3건 중 아무거나가 아니다).
             */
            java.util.UUID latestAssessmentId,
            /** 상위 3건. 화면의 "주요 이슈" 칸. */
            List<MaterialRiskNewsItem> topNews
    ) {}

    /** {@link MaterialRiskSummaryItem}의 상위 뉴스 1건. */
    public record MaterialRiskNewsItem(
            java.util.UUID assessmentId,
            String title,
            BigDecimal score,
            String level,
            OffsetDateTime assessedAt
    ) {}

    /**
     * 대시보드 "공급사 현황 및 대체 공급사 추천".
     *
     * <p>왼쪽(현재 공급사)과 오른쪽(대체 후보)의 <b>원천이 다르다</b> — 현재 공급사는 ERP 발주
     * 실적이고, 대체 후보는 분석이 돌 때 저장된 추천 결과다. 둘을 한 응답에 담는 이유는 화면이
     * 나란히 보여주기 때문이고, 서로 다른 시점을 가리킬 수 있다는 뜻이기도 하다.
     */
    public record SupplierOverview(
            /** 발주 금액(고시환율 원화 환산) 1위 공급사. 발주가 하나도 없으면 null. */
            CurrentSupplier current,
            /**
             * 대체 후보. 가장 최근 분석의 추천을 rank 순으로 최대 3건 준다.
             * 추천이 저장된 분석이 아직 없으면 빈 목록이다.
             */
            List<AlternativeSupplier> alternatives
    ) {}

    /**
     * 현재 주 공급사.
     *
     * <p>{@code dependencyRatio}는 <b>전체 발주 금액 대비 이 공급사 비중</b>이다. 수입 의존도
     * 도넛과 같은 집계를 공급사 단위로 내린 것이라 환산 규칙도 같다 — 통화가 섞인 발주를
     * 한국수출입은행 고시 매매기준율로 원화 환산한 뒤 비중을 낸다. 환산할 수 없는 통화의
     * 발주는 분모·분자 양쪽에서 빠진다(1.0으로 때우면 USD 발주가 1400배 축소된다).
     */
    public record CurrentSupplier(
            String supplierCode,
            String supplierName,
            String countryCode,
            String supplierStatus,
            String riskLevel,
            BigDecimal dependencyRatio
    ) {}

    /**
     * 대체 공급사 후보 1건.
     *
     * <p>{@code recommendationReason}을 반드시 함께 내려보낸다 — "왜 이 공급사인가"가 없으면
     * 구매팀이 화면만 보고는 판단할 수 없다. 이미 저장돼 있는 값이라 추가 계산이 없다.
     *
     * <p>{@code supplierStatus}는 추천 당시 값이 아니라 {@code suppliers}의 <b>현재</b> 상태다.
     * 추천은 과거 분석 시점의 결과지만 승인 상태는 지금 기준이어야 실제로 발주할 수 있는지를
     * 말해준다.
     */
    public record AlternativeSupplier(
            int rankPosition,
            String supplierCode,
            String supplierName,
            String supplierStatus,
            String riskLevel,
            String recommendationReason,
            String pros,
            String cons
    ) {}

    /** 계약 목록 항목. */
    public record ContractItem(
            Long contractId,
            String erpContractId,
            String contractNumber,
            String contractName,
            String erpSupplierId,
            String supplierName,
            String erpMaterialId,
            String materialName,
            String status,
            String contractRole,
            LocalDate startDate,
            LocalDate endDate
    ) {}
}

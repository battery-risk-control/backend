package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.ErpExposureDto;
import com.example.batteryrisk.repository.ErpRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ERP Exposure Agent({@code POST /api/v1/internal/erp/exposure})에 보낼 ERP 입력을 조립한다.
 *
 * <p>이 클래스가 생긴 이유: 같은 Agent를 부르는 두 경로가 각자 입력을 조립하다가 서로 다른 값을
 * 보내기 시작했다. 그러면 <b>같은 자재가 화면마다 다른 점수를 갖는다</b> — 멀티에이전트 브리핑은
 * "코발트 61점 심각", 원자재 위험 화면은 "55점 주의" 같은 식으로. 규칙
 * ({@code fastapi-ai/app/config/erp_rules.yaml})을 한 곳에 둔 의미가 없어진다.
 *
 * <h2>지금은 {@link MaterialRiskService} 한 곳만 쓴다</h2>
 * 원래는 {@link MultiAgentOrchestrationService#buildErpContext}도 이걸 쓰게 해서 조립을 한 벌로
 * 합치려 했지만, <b>멀티에이전트 코드는 병합 작업 중이라 건드리지 않는다</b>(2026-08-02 사용자 지시).
 * 그래서 방향을 뒤집었다 — 이 팩토리가 <b>멀티에이전트가 지금 보내는 것과 똑같은 입력을 만든다.</b>
 * 병합이 끝나면 {@code buildErpContext}가 이 팩토리를 호출하도록 바꿔 한 벌로 합치면 된다.
 *
 * <p>따라서 <b>여기 규칙을 바꿀 때는 {@code MultiAgentOrchestrationService.buildErpContext}와
 * 반드시 함께 바꿔야 한다.</b> 한쪽만 바꾸면 두 화면의 점수가 조용히 갈린다.
 *
 * <h2>멀티에이전트와 맞춘 세 가지</h2>
 * <ol>
 *   <li><b>대체 공급사를 원본 목록으로 보낸다</b>({@code availableCapacityQuantity} 포함).
 *       Agent의 {@code adaptErpExposureRequest()}는 "목록 없이 요약 상태만" 받는 경로도 지원하고
 *       원자재 위험 화면은 한동안 그쪽을 썼는데, 그건 {@code available_capacity_quantity} 컬럼이
 *       없던 시절(V26 이전)의 우회였다. 컬럼이 생긴 지금은 우회를 유지할 이유가 없고, 유지하면
 *       두 경로의 {@code alternativeSupplierStatus} 판정이 갈린다.</li>
 *   <li><b>{@code eligibleForEta}는 납기가 지나지 않은 발주만 true</b>다. 이건 멀티에이전트의
 *       현재 규칙을 그대로 따른 것이다 — <b>더 낫다고 판단해서가 아니다.</b> 납기가 지난 발주를
 *       빼면 Agent의 {@code normalizePurchaseOrderStatus()}(지난 발주를 DELAYED로 승격)가 사실상
 *       죽고, "납기가 지났다"는 위험 신호가 점수에 안 들어간다. 병합 후 함께 재검토할 것.</li>
 *   <li><b>{@code inventorySnapshotAt}은 보내지 않는다(null).</b> 저쪽은 "분석 기준 시각"을 스냅샷
 *       시각인 척 넣는데, 그건 없는 신선도를 지어내는 것이다. <b>점수에는 영향이 없다</b> —
 *       Agent는 값이 있으면 경과 0시간이라 VALID, 없으면 "신선도를 확인하지 못했다" 경고를 달고
 *       역시 VALID를 낸다. 노후 판정은 Spring이 실제 스냅샷 시각으로 직접 한다
 *       ({@link MaterialRiskService}의 재고 노후 판정).</li>
 * </ol>
 *
 * <h2>일부러 남긴 차이 두 개</h2>
 * 필드 단위로 대조했을 때 위 3번 외에 아래 둘이 멀티에이전트와 다르다. <b>둘 다 현재 데이터에서
 * 점수를 바꾸지 않지만</b>, 규칙을 만질 때 알고 있어야 한다.
 * <ul>
 *   <li>{@code supplierStatus}를 Agent 계약에 맞게 변환한다(INACTIVE → SUSPENDED). 멀티에이전트는
 *       DB 값을 그대로 보내는데, {@code SupplierStatus} enum에 INACTIVE가 없어 그런 공급사를 만나면
 *       422로 <b>브리핑이 통째로 실패한다.</b> 지금 데이터에는 ACTIVE·UNDER_REVIEW뿐이라 드러나지
 *       않을 뿐이라, 여기서는 맞춰 보낸다 — 이건 점수 차이가 아니라 저쪽의 결함이다.</li>
 *   <li>대체 공급사를 ERP ID 기준으로 중복 제거한다. 같은 공급사가 두 번 들어가면 Agent가
 *       {@code validateAlternativeSuppliers}에서 422로 거부한다. 중복이 없으면 결과가 완전히 같다.</li>
 * </ul>
 */
@Service
public class ErpExposureContextFactory {

    /** Agent에 넘길 대체 공급사 후보 상한. 멀티에이전트도 같은 값을 쓴다. */
    private static final int ALTERNATIVE_SUPPLIER_LIMIT = 5;

    private final ErpRepository erpRepository;

    public ErpExposureContextFactory(ErpRepository erpRepository) {
        this.erpRepository = erpRepository;
    }

    /**
     * 자재 컨텍스트. {@link ErpService#buildContext}가 계산한 값을 Agent 계약에 맞춰 옮기기만 한다.
     *
     * <p>{@code inventorySnapshotAt}은 항상 null이다(클래스 javadoc 3번).
     */
    public ErpExposureDto.MaterialContext materialContext(ErpDto.ContextResponse erp) {
        return new ErpExposureDto.MaterialContext(
                erp.erpMaterialId(),
                erp.materialName(),
                erp.unit(),
                erp.onHandQuantity(),
                erp.reservedQuantity(),
                erp.blockedQuantity(),
                erp.qualityHoldQuantity(),
                erp.averageDailyUsage(),
                erp.safetyStockQuantity(),
                erp.supplierDependencyRatio(),
                erp.alternativeSupplierStatus(),
                mapSupplierStatus(erp.supplierStatus()),
                erp.erpSupplierId(),
                erp.erpContractId(),
                null);
    }

    /**
     * 미입고 발주 목록. 도착 예정일이 없는 행은 ETA 계산에 쓸 수 없어 제외한다.
     *
     * <p>{@code eligibleForEta}는 <b>도착 예정일이 아직 지나지 않은</b> 발주만 true다
     * (클래스 javadoc 2번 — 멀티에이전트와 맞춘 규칙).
     */
    public List<ErpExposureDto.PurchaseOrderContext> purchaseOrders(
            ErpDto.ContextResponse erp, LocalDate asOfDate) {
        return erpRepository.findOpenPurchaseOrders(erp.materialId(), asOfDate).stream()
                .map(row -> new ErpExposureDto.PurchaseOrderContext(
                        row.erpPurchaseOrderItemId(),
                        row.erpPurchaseOrderId(),
                        row.erpMaterialId(),
                        row.erpSupplierId(),
                        row.erpContractId(),
                        row.remainingQuantity(),
                        row.orderStatus(),
                        row.effectiveArrivalDate() == null ? null : row.effectiveArrivalDate().toString(),
                        row.effectiveArrivalDate() != null
                                && !row.effectiveArrivalDate().isBefore(asOfDate)))
                .toList();
    }

    /**
     * 대체 공급사 후보(클래스 javadoc 1번).
     *
     * <p>{@code supplierStatus}를 ACTIVE로 고정하는 것은 추정이 아니다 —
     * {@link ErpRepository#findEligibleAlternativeSuppliers}가 쿼리에서 이미
     * {@code s.supplier_status = 'ACTIVE'}로 거르고 나온 후보들이다.
     *
     * <p>같은 공급사가 두 번 들어가면 Agent가 {@code validateAlternativeSuppliers}에서 422로
     * 거부한다. 멀티에이전트에는 없는 중복 제거를 여기서 하는 이유는, 쿼리 결과에 중복이 나올
     * 상황(같은 공급사·자재 조합이 두 행)이 데이터상 불가능하지 않기 때문이다. 중복이 없으면
     * 결과가 완전히 같으므로 두 경로의 점수는 어긋나지 않는다.
     */
    public List<ErpExposureDto.AlternativeSupplierContext> alternativeSuppliers(
            ErpDto.ContextResponse erp, LocalDate asOfDate) {
        Set<String> seen = new LinkedHashSet<>();
        List<ErpExposureDto.AlternativeSupplierContext> suppliers = new ArrayList<>();
        for (ErpRepository.AlternativeSupplierRow row : erpRepository.findEligibleAlternativeSuppliers(
                erp.materialId(), erp.primarySupplierId(), asOfDate, ALTERNATIVE_SUPPLIER_LIMIT)) {
            if (row.erpSupplierId() == null || !seen.add(row.erpSupplierId())) {
                continue;
            }
            suppliers.add(new ErpExposureDto.AlternativeSupplierContext(
                    row.erpSupplierId(),
                    null,
                    "ACTIVE",
                    row.availableCapacityQuantity(),
                    row.leadTimeDays(),
                    row.approvedStatus()));
        }
        return suppliers;
    }

    /**
     * 대체 공급사가 얼마나 채워줘야 하는지. Agent의 {@code buildSupplierAssessments}가 후보별
     * capacity 충족도를 볼 때 쓴다. 안전재고 부족분이 곧 "지금 모자란 양"이다
     * (멀티에이전트도 같은 값을 {@code requiredQuantity}로 보낸다).
     */
    public BigDecimal requiredQuantity(ErpDto.ContextResponse erp) {
        return erp.safetyStockShortageQuantity();
    }

    /**
     * 우리 {@code supplier_status}(ACTIVE/UNDER_REVIEW/INACTIVE)를 Agent 계약
     * (ACTIVE/UNDER_REVIEW/SUSPENDED/TERMINATED)에 맞춘다. 안 맞추면 Pydantic이 422를 낸다.
     */
    private static String mapSupplierStatus(String supplierStatus) {
        return "INACTIVE".equals(supplierStatus) ? "SUSPENDED" : supplierStatus;
    }
}

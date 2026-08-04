package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.DashboardDto;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.SupplierRecommendationQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 "공급사 현황 및 대체 공급사 추천".
 *
 * <p><b>새로 계산하는 값이 없다.</b> 왼쪽은 수입 의존도 도넛이 이미 쓰는 ERP 발주 집계를 공급사
 * 단위로 내린 것이고, 오른쪽은 분석이 돌 때 {@code analysis_supplier_recommendations}에 저장해
 * 둔 결과를 꺼내 오는 것뿐이다. 화면만 없었다.
 *
 * <p><b>통화 혼재가 여기서도 함정이다.</b> {@code purchase_orders.currency}가 주문마다 달라
 * 환산 없이 합치면 서로 다른 화폐를 더한 값이 나온다 — {@link ImportDependencyService}와 같은
 * 규칙(한국수출입은행 고시 매매기준율)으로 원화 환산한 뒤에 비중을 낸다. 환산할 수 없는 통화의
 * 발주는 분모·분자 양쪽에서 빼고 경고를 남긴다. 1.0으로 때우면 USD 발주가 원화 금액으로 둔갑해
 * 비중이 1400배 축소되는데, 화면에는 그럴듯한 숫자로 나온다.
 */
@Service
public class SupplierOverviewService {
    private static final Logger log = LoggerFactory.getLogger(SupplierOverviewService.class);

    /** 화면이 보여줄 대체 후보 수. 목업이 3줄이고, rank 4위 이하는 "주력(비대체)"이라 뺀다. */
    private static final int ALTERNATIVE_LIMIT = 3;

    private final ErpRepository erpRepository;
    private final ExchangeRateService exchangeRateService;
    private final SupplierRecommendationQueryRepository recommendationRepository;

    public SupplierOverviewService(
            ErpRepository erpRepository,
            ExchangeRateService exchangeRateService,
            SupplierRecommendationQueryRepository recommendationRepository) {
        this.erpRepository = erpRepository;
        this.exchangeRateService = exchangeRateService;
        this.recommendationRepository = recommendationRepository;
    }

    public DashboardDto.SupplierOverview overview() {
        return new DashboardDto.SupplierOverview(
                topSupplierByOrderAmount(),
                recommendationRepository.findLatestAlternatives(ALTERNATIVE_LIMIT));
    }

    /**
     * 발주 금액(원화 환산) 1위 공급사와 그 비중.
     *
     * <p>"주 공급사"를 별도 플래그로 두지 않고 발주 실적으로 정한다 — ERP에 주력 공급사 플래그가
     * 없고, 있더라도 실제로 얼마를 샀는지가 의존도의 정의에 더 가깝다.
     */
    private DashboardDto.CurrentSupplier topSupplierByOrderAmount() {
        List<ErpRepository.SupplierPurchaseAmountRow> rows =
                erpRepository.aggregatePurchaseAmountsBySupplier();
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Double> rates = exchangeRateService.latestRatesToKrw();
        Map<Long, Double> amountBySupplier = new LinkedHashMap<>();
        Map<Long, ErpRepository.SupplierPurchaseAmountRow> profiles = new LinkedHashMap<>();
        double total = 0;
        for (ErpRepository.SupplierPurchaseAmountRow row : rows) {
            Double rate = rates.get(row.currency());
            if (rate == null) {
                log.warn("공급사 현황: {} 환율이 없어 {} 발주를 제외합니다.", row.currency(), row.supplierCode());
                continue;
            }
            double krw = row.amount().doubleValue() * rate;
            amountBySupplier.merge(row.supplierId(), krw, Double::sum);
            profiles.putIfAbsent(row.supplierId(), row);
            total += krw;
        }
        if (total == 0) {
            log.warn("공급사 현황: 환산 가능한 발주가 없습니다(환율 수집 여부 확인 필요).");
            return null;
        }

        Map.Entry<Long, Double> top = amountBySupplier.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        ErpRepository.SupplierPurchaseAmountRow profile = profiles.get(top.getKey());
        return new DashboardDto.CurrentSupplier(
                profile.supplierCode(), profile.supplierName(), profile.countryCode(),
                profile.supplierStatus(), profile.riskLevel(),
                BigDecimal.valueOf(top.getValue() / total * 100.0).setScale(1, RoundingMode.HALF_UP));
    }
}

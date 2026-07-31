package com.example.batteryrisk.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * kg_service(온톨로지 그래프 리졸버) {@code GET /resolve} 호출 클라이언트.
 *
 * <p>Chain A(실시간 뉴스 분석)가 ERP 노출도 분석 전에 "이 국가+자재 카테고리가 실제로 우리 공급망과
 * 관련 있는지, 재고가 부족한지"를 KG에게 먼저 물어보기 위한 것 — 지금까지 이 호출자가 없어서
 * {@code ErpExposureRequestService}가 국가를 무시하고 카테고리 안의 아무 자재나 골라 쓰고 있었다.
 *
 * <p>부가 기능이라 실패해도 예외를 던지지 않고 "매칭 없음"으로 폴백한다(ErpAdminService의
 * KG 동기화 실패 처리와 같은 방침).
 */
@Service
public class KgResolverService {
    private static final Logger log = LoggerFactory.getLogger(KgResolverService.class);

    private final RestClient kgServiceRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KgResolverService(RestClient kgServiceRestClient) {
        this.kgServiceRestClient = kgServiceRestClient;
    }

    /**
     * country + materialCategory로 KG를 조회해, 재고부족이 확정된 첫 공급사를 돌려준다.
     * 매칭 없음/재고충분/호출실패는 전부 {@link KgResolveResult#NOT_MATCHED}로 동일하게 처리한다
     * (호출부는 shortageConfirmed()만 보면 되고, 실패 원인을 구분할 필요가 없다).
     */
    public KgResolveResult resolve(String countryCode, String materialCategory) {
        if (countryCode == null || countryCode.isBlank()
                || materialCategory == null || materialCategory.isBlank()) {
            return KgResolveResult.NOT_MATCHED;
        }
        try {
            String affectedMaterialsJson = objectMapper.writeValueAsString(List.of(materialCategory));
            KgResolveResponse response = kgServiceRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/resolve")
                            .queryParam("country", countryCode)
                            .queryParam("affected_materials", affectedMaterialsJson)
                            .build())
                    .retrieve()
                    .body(KgResolveResponse.class);
            return toResult(response);
        } catch (Exception exception) {
            log.warn("kg_service /resolve 호출 실패 (country={}, materialCategory={}): {}",
                    countryCode, materialCategory, exception.getMessage());
            return KgResolveResult.NOT_MATCHED;
        }
    }

    private KgResolveResult toResult(KgResolveResponse response) {
        if (response == null || !response.matched()
                || response.results() == null || response.results().isEmpty()) {
            return KgResolveResult.NOT_MATCHED;
        }
        for (KgResolveResultItem item : response.results()) {
            List<String> suppliers = item.affectedSuppliers();
            if (suppliers == null || suppliers.isEmpty()) {
                continue;
            }
            boolean shortage = item.inventoryChecks() != null
                    && item.inventoryChecks().values().stream()
                            .anyMatch(check -> "SHORTAGE".equals(check.get("status")));
            if (shortage) {
                return new KgResolveResult(true, suppliers.get(0));
            }
        }
        return KgResolveResult.NOT_MATCHED;
    }

    public record KgResolveResult(boolean shortageConfirmed, String resolvedErpSupplierId) {
        public static final KgResolveResult NOT_MATCHED = new KgResolveResult(false, null);
    }

    // kg_service의 /resolve 응답(kg_service/main.py)과 필드 이름을 맞춘 DTO.
    private record KgResolveResponse(
            @JsonProperty("matched") boolean matched,
            @JsonProperty("results") List<KgResolveResultItem> results) {}

    private record KgResolveResultItem(
            @JsonProperty("affected_suppliers") List<String> affectedSuppliers,
            @JsonProperty("inventory_checks") Map<String, Map<String, Object>> inventoryChecks) {}
}

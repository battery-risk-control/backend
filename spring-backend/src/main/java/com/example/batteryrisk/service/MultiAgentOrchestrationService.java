package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.MultiAgentDto;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.example.batteryrisk.repository.ErpRepository;

@Service
public class MultiAgentOrchestrationService {
    private final ErpService erpService;
    private final MultiAgentService multiAgentService;
    private final ErpRepository erpRepository;

    public MultiAgentOrchestrationService(
        ErpService erpService,
        MultiAgentService multiAgentService,
        ErpRepository erpRepository
) {
    this.erpService = erpService;
    this.multiAgentService = multiAgentService;
    this.erpRepository = erpRepository;
}

    public MultiAgentDto.Response generate(
            MultiAgentDto.GenerateRequest request
    ) {
        ErpDto.ContextResponse erp =
                erpService.buildContext(
                        new ErpDto.ContextRequest(
                                request.erpMaterialId(),
                                request.erpSupplierId(),
                                request.asOf()
                        )
                );

        String impactDomain =
                normalizeImpactDomain(
                        request.impactDomainFinal()
                );

        boolean erpAnalysisRequired =
                !impactDomain.equals("OTHER_IRRELEVANT");

        Map<String, Object> erpContext =
                buildErpContext(
                        request,
                        erp,
                        impactDomain,
                        erpAnalysisRequired
                );

        MultiAgentDto.Request fastApiRequest =
                new MultiAgentDto.Request(
                        request.newsId(),
                        request.title(),
                        request.articleText(),
                        request.summaryKr(),
                        request.impactDomainDraft(),
                        request.impactDomainFinal(),
                        List.of(erp.materialName()),
                        request.country(),
                        request.externalSignalLevel()
                                .toLowerCase(Locale.ROOT),
                        request.externalSignalScore(),
                        erpContext,
                        erp.primaryContractId(),
                        erp.primarySupplierId(),
                        erp.materialId(),
                        request.useLlm()
                );

        return multiAgentService.generate(
                fastApiRequest
        );
    }

    private Map<String, Object> buildErpContext(
            MultiAgentDto.GenerateRequest request,
            ErpDto.ContextResponse erp,
            String impactDomain,
            boolean erpAnalysisRequired
    ) {
        Map<String, Object> context =
                new LinkedHashMap<>();

        context.put(
                "requestId",
                "ERP-" + UUID.randomUUID()
        );
        context.put("eventId", request.newsId());
        context.put("asOf", request.asOf());
        context.put("impactDomain", impactDomain);
        context.put(
                "externalSignalScore",
                request.externalSignalScore()
        );
        context.put(
                "externalSignalLevel",
                request.externalSignalLevel()
                        .toUpperCase(Locale.ROOT)
        );
        context.put(
                "affectedMaterialId",
                erp.erpMaterialId()
        );
        context.put(
                "affectedSupplierId",
                erp.erpSupplierId()
        );
        context.put(
                "primaryContractId",
                erp.erpContractId()
        );
        context.put(
                "eventSummary",
                resolveEventSummary(request)
        );
        context.put(
                "erpAnalysisRequired",
                erpAnalysisRequired
        );

        if (erpAnalysisRequired) {
            context.put(
                    "materialContext",
                    buildMaterialContext(erp)
            );
        }

        /*
         * 다음 단계에서 실제 발주 상세와
         * 대체 공급사 목록을 PostgreSQL에서 연결한다.
         */
        context.put(
        "purchaseOrders",
        erpRepository.findOpenPurchaseOrders(
                        erp.materialId(),
                        request.asOf().toLocalDate()
                )
                .stream()
                .map(row -> {
                    Map<String, Object> order =
                            new LinkedHashMap<>();

                    order.put(
                            "purchaseOrderItemId",
                            row.erpPurchaseOrderItemId()
                    );
                    order.put(
                            "purchaseOrderId",
                            row.erpPurchaseOrderId()
                    );
                    order.put(
                            "materialId",
                            row.erpMaterialId()
                    );
                    order.put(
                            "supplierId",
                            row.erpSupplierId()
                    );
                    order.put(
                            "contractId",
                            row.erpContractId()
                    );
                    order.put(
                            "remainingQuantity",
                            row.remainingQuantity()
                    );
                    order.put(
                            "orderStatus",
                            row.orderStatus()
                    );
                    order.put(
                            "effectiveArrivalDate",
                            row.effectiveArrivalDate() == null
                            ? null
                            : row.effectiveArrivalDate().toString()
                    );
                    order.put(
                            "eligibleForEta",
                            row.effectiveArrivalDate() != null
                                    && !row.effectiveArrivalDate()
                                    .isBefore(
                                            request.asOf()
                                                    .toLocalDate()
                                    )
                    );

                    return order;
                })
                .toList()
);

context.put(
        "alternativeSuppliers",
        erpRepository
                .findEligibleAlternativeSuppliers(
                        erp.materialId(),
                        erp.primarySupplierId(),
                        request.asOf().toLocalDate(),
                        5
                )
                .stream()
                .map(row -> {
                    Map<String, Object> supplier =
                            new LinkedHashMap<>();

                    supplier.put(
                            "supplierId",
                            row.erpSupplierId()
                    );
                    supplier.put("contractId", null);
                    supplier.put(
                            "supplierStatus",
                            "ACTIVE"
                    );
                    supplier.put(
                            "availableCapacityQuantity",
                            null
                    );
                    supplier.put(
                            "leadTimeDays",
                            row.leadTimeDays()
                    );
                    supplier.put(
                            "qualificationStatus",
                            row.approvedStatus()
                    );

                    return supplier;
                })
                .toList()
);

        return context;
    }

    private static Map<String, Object> buildMaterialContext(
            ErpDto.ContextResponse erp
    ) {
        Map<String, Object> material =
                new LinkedHashMap<>();

        material.put(
                "materialId",
                erp.erpMaterialId()
        );
        material.put(
                "materialName",
                erp.materialName()
        );
        material.put("unit", erp.unit());
        material.put(
                "onHandQuantity",
                erp.onHandQuantity()
        );
        material.put(
                "reservedQuantity",
                erp.reservedQuantity()
        );
        material.put(
                "blockedQuantity",
                erp.blockedQuantity()
        );
        material.put(
                "qualityHoldQuantity",
                erp.qualityHoldQuantity()
        );
        material.put(
                "averageDailyUsage",
                erp.averageDailyUsage()
        );
        material.put(
                "safetyStockQuantity",
                erp.safetyStockQuantity()
        );
        material.put(
                "supplierDependencyRatio",
                erp.supplierDependencyRatio()
        );
        material.put(
                "alternativeSupplierStatus",
                erp.alternativeSupplierStatus()
        );
        material.put(
                "supplierStatus",
                erp.supplierStatus()
        );
        material.put(
                "primarySupplierId",
                erp.erpSupplierId()
        );
        material.put(
                "primaryContractId",
                erp.erpContractId()
        );
        material.put(
                "inventorySnapshotAt",
                erp.asOf()
        );

        return material;
    }

    private static String resolveEventSummary(
            MultiAgentDto.GenerateRequest request
    ) {
        if (request.summaryKr() != null
                && !request.summaryKr().isBlank()) {
            return request.summaryKr();
        }

        return request.title();
    }

    private static String normalizeImpactDomain(
            String value
    ) {
        String normalized = value
                .trim()
                .toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "production" -> "PRODUCTION";
            case "logistics" -> "LOGISTICS";
            case "policy" -> "POLICY";
            case "market" -> "MARKET";
            case "geopolitical", "geopolitics" ->
                    "GEOPOLITICS";
            default -> "OTHER_IRRELEVANT";
        };
    }
}
package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.BriefingDto;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.BriefingRepository;
import com.example.batteryrisk.repository.ErpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 13단계 템플릿 브리핑 오케스트레이션.
 *
 * <p>ERP Context(S10) → Severity(S11) → 계약 근거 RAG 검색(F2) → 적격 대체 공급사(F9)를 모아
 * FastAPI 템플릿 조립을 호출하고 결과를 PostgreSQL에 저장한다. 재고 관점과 계약 관점은
 * 하나의 확정 결론으로 합치지 않고 각각 별도 섹션으로 보존한다.
 */
@Service
public class BriefingService {
    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
    private static final Set<String> LEVELS = Set.of("NORMAL", "WARNING", "CRITICAL", "UNKNOWN");
    private static final int MAX_ALTERNATIVE_SUPPLIERS = 5;

    private final ErpService erpService;
    private final RagService ragService;
    private final ErpRepository erpRepository;
    private final BriefingRepository repository;
    private final RestClient fastApiRestClient;

    public BriefingService(
            ErpService erpService,
            RagService ragService,
            ErpRepository erpRepository,
            BriefingRepository repository,
            RestClient fastApiRestClient) {
        this.erpService = erpService;
        this.ragService = ragService;
        this.erpRepository = erpRepository;
        this.repository = repository;
        this.fastApiRestClient = fastApiRestClient;
    }

    public BriefingDto.BriefingResponse generate(BriefingDto.GenerateRequest request) {
        ErpDto.ContextResponse context = erpService.buildContext(new ErpDto.ContextRequest(
                request.erpMaterialId(), request.erpSupplierId(), request.asOf()));

        SeverityDto.FastApiResult severity = callSeverity(context, request);
        List<BriefingDto.ContractEvidence> evidence = searchContractEvidence(context, request);
        List<BriefingDto.AlternativeSupplier> alternatives = findAlternatives(context, request);

        BriefingDto.FastApiComposeResult composed = callCompose(new BriefingDto.FastApiComposeRequest(
                context.erpMaterialId(),
                context.materialName(),
                context.unit(),
                severity.severity(),
                severity.score(),
                severity.reasonCodes(),
                context.inventoryDays(),
                context.safetyStockDays(),
                context.availableQuantity(),
                context.expectedSupplyGapDays(),
                context.nextEtaDays(),
                context.supplierDependencyRatio(),
                context.erpSupplierId(),
                context.supplierStatus(),
                context.feocStatus(),
                context.dataQualityStatus(),
                evidence,
                alternatives,
                context.mock()
        ));

        BriefingDto.BriefingResponse briefing = new BriefingDto.BriefingResponse(
                UUID.randomUUID(),
                context.materialId(),
                context.primarySupplierId(),
                context.erpMaterialId(),
                context.erpSupplierId(),
                request.asOf(),
                severity.severity(),
                severity.score(),
                composed.headline(),
                composed.riskSummary(),
                composed.inventorySummary(),
                composed.supplierDependencySummary(),
                composed.supplyGapSummary(),
                composed.contractEvidenceSummary(),
                composed.alternativeSupplierSummary(),
                List.copyOf(severity.reasonCodes()),
                List.copyOf(composed.recommendedChecks()),
                List.copyOf(composed.warnings()),
                evidence,
                alternatives,
                composed.templateVersion(),
                severity.ruleVersion(),
                composed.mock(),
                OffsetDateTime.now()
        );
        repository.save(briefing);
        return briefing;
    }

    public BriefingDto.BriefingResponse get(UUID briefingId) {
        return repository.findById(briefingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_NOT_FOUND));
    }

    private SeverityDto.FastApiResult callSeverity(
            ErpDto.ContextResponse context, BriefingDto.GenerateRequest request) {
        SeverityDto.FastApiRequest severityRequest = new SeverityDto.FastApiRequest(
                context.inventoryDays(),
                context.safetyStockDays(),
                context.expectedSupplyGapDays(),
                context.supplierDependencyRatio(),
                request.priceChangeRate(),
                request.logisticsDelayDays(),
                request.gdacsAlertLevel(),
                context.feocStatus(),
                context.dataQualityStatus());

        SeverityDto.FastApiResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/internal/severity/score")
                    .body(severityRequest)
                    .retrieve()
                    .body(SeverityDto.FastApiResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI Severity failed during briefing: status={}", exception.getStatusCode());
            throw new BusinessException(ErrorCode.FASTAPI_SEVERITY_UNAVAILABLE);
        } catch (Exception exception) {
            log.warn("FastAPI Severity connection failed during briefing", exception);
            throw new BusinessException(ErrorCode.FASTAPI_SEVERITY_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.INVALID_SEVERITY_RESPONSE);
        }
        SeverityDto.FastApiResult result = response.data();
        BigDecimal score = result.score();
        if (!LEVELS.contains(result.severity())
                || score == null || score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0
                || result.reasonCodes() == null || result.reasonCodes().isEmpty()
                || result.ruleVersion() == null || result.ruleVersion().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SEVERITY_RESPONSE);
        }
        return result;
    }

    /** 계약 근거는 자재·공급사 Metadata Filter 범위에서만 검색한다. */
    private List<BriefingDto.ContractEvidence> searchContractEvidence(
            ErpDto.ContextResponse context, BriefingDto.GenerateRequest request) {
        String query = request.query() == null || request.query().isBlank()
                ? context.materialName()
                : request.query();
        RagDto.SearchResult searchResult = ragService.search(new RagDto.SearchRequest(
                query,
                new RagDto.SearchFilters(null, context.primarySupplierId(), context.materialId()),
                request.resolvedTopK()));
        return searchResult.results().stream()
                .map(item -> new BriefingDto.ContractEvidence(
                        item.documentId(), item.contractId(), item.pageNumber(),
                        item.content(), item.similarityScore()))
                .toList();
    }

    /** 자격 미달 후보는 Spring에서 제거하고 적격 후보만 FastAPI에 전달한다. */
    private List<BriefingDto.AlternativeSupplier> findAlternatives(
            ErpDto.ContextResponse context, BriefingDto.GenerateRequest request) {
        return erpRepository.findEligibleAlternativeSuppliers(
                        context.materialId(),
                        context.primarySupplierId(),
                        request.asOf().toLocalDate(),
                        MAX_ALTERNATIVE_SUPPLIERS)
                .stream()
                .map(row -> new BriefingDto.AlternativeSupplier(
                        row.erpSupplierId(), row.supplierName(), row.approvedStatus(),
                        row.feocStatus(), row.iatf16949Certified(), row.ppapApproved(),
                        row.leadTimeDays()))
                .toList();
    }

    private BriefingDto.FastApiComposeResult callCompose(BriefingDto.FastApiComposeRequest request) {
        BriefingDto.FastApiComposeResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/internal/briefings/compose")
                    .body(request)
                    .retrieve()
                    .body(BriefingDto.FastApiComposeResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI briefing compose failed: status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.FASTAPI_BRIEFING_UNAVAILABLE);
        } catch (Exception exception) {
            log.warn("FastAPI briefing compose connection failed", exception);
            throw new BusinessException(ErrorCode.FASTAPI_BRIEFING_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.INVALID_BRIEFING_RESPONSE);
        }
        BriefingDto.FastApiComposeResult result = response.data();
        if (result.headline() == null || result.headline().isBlank()
                || result.templateVersion() == null || result.templateVersion().isBlank()
                || result.recommendedChecks() == null || result.warnings() == null) {
            throw new BusinessException(ErrorCode.INVALID_BRIEFING_RESPONSE);
        }
        return result;
    }
}

package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.BriefingDto;
import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.PageResponse;
import com.example.batteryrisk.dto.RagDto;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.BriefingRepository;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.repository.SeverityRepository;
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
    private final SeverityRepository severityRepository;
    private final RestClient fastApiRestClient;

    public BriefingService(
            ErpService erpService,
            RagService ragService,
            ErpRepository erpRepository,
            BriefingRepository repository,
            SeverityRepository severityRepository,
            RestClient fastApiRestClient) {
        this.erpService = erpService;
        this.ragService = ragService;
        this.erpRepository = erpRepository;
        this.repository = repository;
        this.severityRepository = severityRepository;
        this.fastApiRestClient = fastApiRestClient;
    }

    public BriefingDto.BriefingResponse generate(BriefingDto.GenerateRequest request) {
        ErpDto.ContextResponse context = erpService.buildContext(new ErpDto.ContextRequest(
                request.erpMaterialId(), request.erpSupplierId(), request.asOf()));

        SeverityDto.FastApiResult severity = callSeverity(context, request);
        // 브리핑이 계산한 위험 등급도 분석 이력으로 남긴다 — 대시보드 집계와 근거 계보(F7)의 기준이 된다.
        // 저장한 판정 ID를 브리핑에 연결해 "이 브리핑 → 이 Severity 판정 → ERP 입력 스냅샷"을 역추적할 수 있게 한다.
        UUID assessmentId = saveSeverityAssessment(context, request, severity);
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
                assessmentId,
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

    /**
     * F7 근거 계보 조회(Phase 2).
     *
     * <p>브리핑 결론에서 시작해 근거를 되짚어 조립한다: Severity 판정(ERP 입력 스냅샷 포함)과 계약 근거를 함께 반환한다.
     * 옛 브리핑(assessment_id 없음)은 링크가 없으므로 판정은 null로 두고 브리핑에 딸린 근거만 보여준다.
     */
    public BriefingDto.LineageResponse getLineage(UUID briefingId) {
        BriefingDto.BriefingResponse briefing = repository.findById(briefingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_NOT_FOUND));
        SeverityDto.AssessmentResponse assessment = briefing.assessmentId() == null
                ? null
                : severityRepository.findById(briefing.assessmentId()).orElse(null);
        return new BriefingDto.LineageResponse(
                briefing.briefingId(),
                briefing.erpMaterialId(),
                briefing.erpSupplierId(),
                briefing.severity(),
                briefing.score(),
                briefing.headline(),
                briefing.assessedAt(),
                briefing.createdAt(),
                briefing.reasonCodes(),
                briefing.warnings(),
                briefing.templateVersion(),
                briefing.ruleVersion(),
                briefing.mock(),
                assessment,
                briefing.contractEvidence());
    }

    /** 목록 조회 — 화면 첫 진입 시 최근 브리핑을 보여주기 위한 API. */
    public PageResponse<BriefingDto.BriefingListItem> list(
            String severity, String erpMaterialId, int page, int size) {
        String normalizedSeverity = blankToNull(severity);
        String normalizedMaterial = blankToNull(erpMaterialId);
        if (normalizedSeverity != null && !LEVELS.contains(normalizedSeverity)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "severity는 NORMAL/WARNING/CRITICAL/UNKNOWN 중 하나여야 합니다.");
        }
        List<BriefingDto.BriefingListItem> content =
                repository.findPage(normalizedSeverity, normalizedMaterial, page, size);
        long total = repository.countAll(normalizedSeverity, normalizedMaterial);
        return PageResponse.of(content, page, size, total);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
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

    /**
     * 브리핑이 계산한 Severity를 severity_assessments에도 저장해 대시보드 집계에 반영되게 한다.
     * 저장한 판정의 assessment_id를 반환해 브리핑이 근거 계보(F7)로 연결할 수 있게 한다.
     */
    private UUID saveSeverityAssessment(
            ErpDto.ContextResponse context,
            BriefingDto.GenerateRequest request,
            SeverityDto.FastApiResult severity) {
        UUID assessmentId = UUID.randomUUID();
        severityRepository.save(new SeverityDto.AssessmentResponse(
                assessmentId,
                context.materialId(),
                context.primarySupplierId(),
                context.erpMaterialId(),
                context.erpSupplierId(),
                request.asOf(),
                context.inventoryDays(),
                context.safetyStockDays(),
                context.expectedSupplyGapDays(),
                context.supplierDependencyRatio(),
                request.priceChangeRate(),
                request.logisticsDelayDays(),
                request.gdacsAlertLevel(),
                context.feocStatus(),
                context.dataQualityStatus(),
                severity.severity(),
                severity.score(),
                List.copyOf(severity.reasonCodes()),
                new java.util.LinkedHashMap<>(severity.calculationDetails()),
                severity.ruleVersion(),
                severity.mock(),
                OffsetDateTime.now()));
        return assessmentId;
    }

    /** 계약 근거는 자재·공급사 Metadata Filter 범위에서만 검색한다. */
    private List<BriefingDto.ContractEvidence> searchContractEvidence(
            ErpDto.ContextResponse context, BriefingDto.GenerateRequest request) {
        String query = request.query() == null || request.query().isBlank()
                ? context.materialName()
                : request.query();
        RagDto.SearchResult searchResult = ragService.search(new RagDto.SearchRequest(
                query,
                new RagDto.SearchFilters(null, context.primarySupplierId(), context.materialId(), null, null),
                request.resolvedTopK()));
        return searchResult.results().stream()
                .map(item -> new BriefingDto.ContractEvidence(
                        item.documentId(), item.contractId(), item.pageNumber(),
                        item.chunkIndex(), item.content(), item.similarityScore()))
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

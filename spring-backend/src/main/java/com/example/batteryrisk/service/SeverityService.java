package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpDto;
import com.example.batteryrisk.dto.SeverityDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.SeverityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Spring ERP Context와 외부 신호를 결합해 FastAPI Severity를 호출하고 결과를 저장합니다. */
@Service
public class SeverityService {
    private static final Logger log = LoggerFactory.getLogger(SeverityService.class);
    private static final Set<String> LEVELS = Set.of("NORMAL", "WARNING", "CRITICAL", "UNKNOWN");

    private final ErpService erpService;
    private final SeverityRepository repository;
    private final RestClient fastApiRestClient;

    public SeverityService(
            ErpService erpService,
            SeverityRepository repository,
            RestClient fastApiRestClient) {
        this.erpService = erpService;
        this.repository = repository;
        this.fastApiRestClient = fastApiRestClient;
    }

    public SeverityDto.AssessmentResponse assess(SeverityDto.AssessmentRequest request) {
        ErpDto.ContextResponse context = erpService.buildContext(new ErpDto.ContextRequest(
                request.erpMaterialId(), request.erpSupplierId(), request.asOf()));

        SeverityDto.FastApiRequest fastApiRequest = new SeverityDto.FastApiRequest(
                context.inventoryDays(),
                context.safetyStockDays(),
                context.expectedSupplyGapDays(),
                context.supplierDependencyRatio(),
                request.priceChangeRate(),
                request.logisticsDelayDays(),
                request.gdacsAlertLevel(),
                context.feocStatus(),
                context.dataQualityStatus()
        );
        SeverityDto.FastApiResult result = callFastApi(fastApiRequest);
        OffsetDateTime createdAt = OffsetDateTime.now();
        SeverityDto.AssessmentResponse assessment = new SeverityDto.AssessmentResponse(
                UUID.randomUUID(),
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
                result.severity(),
                result.score(),
                List.copyOf(result.reasonCodes()),
                new LinkedHashMap<>(result.calculationDetails()),
                result.ruleVersion(),
                result.mock(),
                createdAt
        );
        repository.save(assessment);
        return assessment;
    }

    public SeverityDto.AssessmentResponse get(UUID assessmentId) {
        return repository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEVERITY_ASSESSMENT_NOT_FOUND));
    }

    private SeverityDto.FastApiResult callFastApi(SeverityDto.FastApiRequest request) {
        SeverityDto.FastApiResponse response;
        try {
            response = fastApiRestClient.post()
                    .uri("/api/v1/internal/severity/score")
                    .body(request)
                    .retrieve()
                    .body(SeverityDto.FastApiResponse.class);
        } catch (RestClientResponseException exception) {
            log.warn("FastAPI Severity failed: status={}", exception.getStatusCode());
            throw new BusinessException(ErrorCode.FASTAPI_SEVERITY_UNAVAILABLE);
        } catch (Exception exception) {
            log.warn("FastAPI Severity connection failed", exception);
            throw new BusinessException(ErrorCode.FASTAPI_SEVERITY_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.INVALID_SEVERITY_RESPONSE);
        }
        validate(response.data());
        return response.data();
    }

    private static void validate(SeverityDto.FastApiResult result) {
        BigDecimal score = result.score();
        Map<String, Object> details = result.calculationDetails();
        if (!LEVELS.contains(result.severity())
                || score == null || score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0
                || result.reasonCodes() == null || result.reasonCodes().isEmpty()
                || details == null
                || result.ruleVersion() == null || result.ruleVersion().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SEVERITY_RESPONSE);
        }
    }
}

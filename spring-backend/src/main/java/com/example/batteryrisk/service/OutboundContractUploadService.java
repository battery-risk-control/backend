package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpAdminDto;
import com.example.batteryrisk.dto.OutboundContractUploadDto;
import com.example.batteryrisk.dto.OutboundDocumentDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.exception.GlobalExceptionHandler.DocumentUploadException;
import com.example.batteryrisk.repository.ErpRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) 계약서 업로드 → CTR-OUT-XXX 자동 발급 +
 * ERP/RAG/KG 동기화. {@link ContractUploadService}의 아웃바운드 버전 — 구조는 동일하되
 * 인바운드의 SM-XXX(공급사-자재) 채번 단계가 없다(outbound_contracts가 product_id/customer_id를
 * 직접 갖고 있어 별도 junction 테이블이 필요 없음). 아웃바운드 계약엔 발효일/만료일 개념이
 * 없고 대신 물량(GWh)/단가(USD/kWh)/납기일수/위약금(%)이 있다.
 */
@Service
public class OutboundContractUploadService {
    private static final Logger log = LoggerFactory.getLogger(OutboundContractUploadService.class);

    private final RestClient fastApiRestClient;
    private final RestClient kgServiceRestClient;
    private final ErpRepository repository;
    private final ErpAdminService erpAdminService;
    private final OutboundDocumentService outboundDocumentService;
    private final Path outboundErpSeedDirectory;

    public OutboundContractUploadService(
            RestClient fastApiRestClient,
            RestClient kgServiceRestClient,
            ErpRepository repository,
            ErpAdminService erpAdminService,
            OutboundDocumentService outboundDocumentService,
            @Value("${app.erp.outbound-seed.directory:}") String outboundErpSeedDirectory) {
        this.fastApiRestClient = fastApiRestClient;
        this.kgServiceRestClient = kgServiceRestClient;
        this.repository = repository;
        this.erpAdminService = erpAdminService;
        this.outboundDocumentService = outboundDocumentService;
        this.outboundErpSeedDirectory = outboundErpSeedDirectory == null || outboundErpSeedDirectory.isBlank()
                ? null : Path.of(outboundErpSeedDirectory).toAbsolutePath().normalize();
    }

    /** 1단계: 파일에서 텍스트를 뽑아 계약 필드를 미리 채워 보여준다. DB에는 아무것도 안 쓴다. */
    public OutboundContractUploadDto.PreviewResponse preview(
            MultipartFile file, String erpProductId, String erpCustomerId) {
        long productId = requireFk(repository.resolveProductId(erpProductId), ErrorCode.ERP_PRODUCT_NOT_FOUND);
        long customerId = requireFk(repository.resolveCustomerId(erpCustomerId), ErrorCode.ERP_CUSTOMER_NOT_FOUND);

        String text = extractText(file);
        OutboundContractFieldExtractor.ExtractedFields fields = OutboundContractFieldExtractor.extract(text);

        Optional<ErpRepository.OutboundContractRef> existing =
                repository.findOutboundContractForProductCustomer(productId, customerId);
        String expectedNewId = existing.isPresent() ? null : nextOutboundContractId();

        return new OutboundContractUploadDto.PreviewResponse(
                erpProductId, erpCustomerId,
                existing.map(ErpRepository.OutboundContractRef::erpOutboundContractId).orElse(null),
                expectedNewId,
                fields.contractLanguage(), fields.quantityGwh(), fields.unitPriceUsdKwh(),
                fields.deliveryLeadTimeDays(), fields.penaltyPct());
    }

    /**
     * 2단계: 미리보기에서 확인/수정한 값으로 실제 생성한다. (제품,고객사) 조합에 계약이 이미
     * 있으면 새로 안 만들고 그 계약에 문서만 추가한다. 인바운드 {@link ContractUploadService#confirm}
     * 처럼 필드가 비어 있으면 파일에서 정규식으로 자동 추출한다 — "제품+고객사+파일만 주면 끝".
     */
    public OutboundContractUploadDto.ConfirmResponse confirm(
            MultipartFile file, String erpProductId, String erpCustomerId,
            String contractLanguage, BigDecimal quantityGwh, BigDecimal unitPriceUsdKwh,
            Integer deliveryLeadTimeDays, BigDecimal penaltyPct,
            BigDecimal lineStopChargeUsd, BigDecimal lineStopChargeKrw) {
        long productId = requireFk(repository.resolveProductId(erpProductId), ErrorCode.ERP_PRODUCT_NOT_FOUND);
        long customerId = requireFk(repository.resolveCustomerId(erpCustomerId), ErrorCode.ERP_CUSTOMER_NOT_FOUND);

        Optional<ErpRepository.OutboundContractRef> existing =
                repository.findOutboundContractForProductCustomer(productId, customerId);

        long outboundContractId;
        String erpOutboundContractId;
        boolean created;

        if (existing.isPresent()) {
            outboundContractId = existing.get().outboundContractId();
            erpOutboundContractId = existing.get().erpOutboundContractId();
            created = false;
        } else {
            if (isBlank(contractLanguage) || quantityGwh == null || unitPriceUsdKwh == null
                    || deliveryLeadTimeDays == null || penaltyPct == null) {
                OutboundContractFieldExtractor.ExtractedFields extracted =
                        OutboundContractFieldExtractor.extract(extractText(file));
                if (isBlank(contractLanguage)) {
                    contractLanguage = extracted.contractLanguage();
                }
                if (quantityGwh == null) {
                    quantityGwh = extracted.quantityGwh();
                }
                if (unitPriceUsdKwh == null) {
                    unitPriceUsdKwh = extracted.unitPriceUsdKwh();
                }
                if (deliveryLeadTimeDays == null) {
                    deliveryLeadTimeDays = extracted.deliveryLeadTimeDays();
                }
                if (penaltyPct == null) {
                    penaltyPct = extracted.penaltyPct();
                }
            }
            if (isBlank(contractLanguage) || quantityGwh == null || unitPriceUsdKwh == null
                    || deliveryLeadTimeDays == null || penaltyPct == null) {
                throw new DocumentUploadException(
                        "OUTBOUND_CONTRACT_FIELDS_REQUIRED",
                        "계약언어/물량/단가/납기일수/위약금을 파일에서 자동으로 찾지 못했습니다. 직접 입력해주세요.");
            }
            // 미리보기 때 보여준 CTR-OUT-XXX는 예상값일 뿐 — 확정 시점에 다시 계산한다
            // (인바운드와 같은 "채번 경합" 단순화 처리).
            erpOutboundContractId = nextOutboundContractId();

            ErpAdminDto.UpsertResponse contractResponse = erpAdminService.upsertOutboundContract(
                    new ErpAdminDto.OutboundContractUpsertRequest(
                            erpOutboundContractId, erpProductId, erpCustomerId, "LG에너지솔루션",
                            quantityGwh, unitPriceUsdKwh, penaltyPct,
                            lineStopChargeUsd, lineStopChargeKrw, deliveryLeadTimeDays, contractLanguage));
            outboundContractId = contractResponse.internalId();
            created = true;

            appendOutboundContractsCsvRow(
                    erpOutboundContractId, erpProductId, erpCustomerId, quantityGwh, unitPriceUsdKwh,
                    penaltyPct, lineStopChargeUsd, lineStopChargeKrw, deliveryLeadTimeDays, contractLanguage);
            syncKgService(
                    erpOutboundContractId, erpProductId, erpCustomerId, quantityGwh, unitPriceUsdKwh,
                    penaltyPct, lineStopChargeUsd, lineStopChargeKrw, deliveryLeadTimeDays, contractLanguage);
        }

        OutboundDocumentDto.UploadResponse uploadResponse = outboundDocumentService.upload(
                file, outboundContractId, productId, customerId, "CONTRACT");

        return new OutboundContractUploadDto.ConfirmResponse(
                erpOutboundContractId, created, uploadResponse.documentId(), uploadResponse.processingStatus());
    }

    private String extractText(MultipartFile file) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        String contentType = file.getContentType();
        fileHeaders.setContentType(
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
                .name("file").filename(file.getOriginalFilename()).build());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new DocumentUploadException("FILE_READ_FAILED", "파일을 읽을 수 없습니다.");
        }
        parts.add("file", new HttpEntity<>(
                new NamedByteArrayResource(content, file.getOriginalFilename()), fileHeaders));

        try {
            ExtractTextFastApiResponse response = fastApiRestClient.post()
                    .uri("/api/v1/documents/extract-text")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(ExtractTextFastApiResponse.class);
            if (response == null || !response.success() || response.data() == null) {
                throw new DocumentUploadException(
                        "INVALID_FASTAPI_RESPONSE", "FastAPI 텍스트 추출 응답이 올바르지 않습니다.");
            }
            return response.data().text();
        } catch (DocumentUploadException exception) {
            throw exception;
        } catch (Exception exception) {
            // 텍스트 추출은 어디까지나 자동 채움 편의 기능 — 실패해도 미리보기는 빈 필드로
            // 계속 진행하고 사용자가 직접 입력하게 한다(인바운드와 동일 정책).
            log.warn("아웃바운드 계약서 텍스트 추출 실패, 필드 자동 채움 없이 진행합니다", exception);
            return "";
        }
    }

    private String nextOutboundContractId() {
        int next = repository.findMaxOutboundContractSequence().map(n -> n + 1).orElse(1);
        return "CTR-OUT-%03d".formatted(next);
    }

    /**
     * kg_service에 새 아웃바운드 계약 정보를 보내 자기 쪽 outbound_contracts.csv에도 append하고
     * 그래프를 다시 만들게 한다. 인바운드 {@link ContractUploadService#syncKgService}와 같은
     * 이유(Docker가 Google Drive 가상 드라이브를 못 읽는 문제 우회)로 같은 패턴을 쓴다.
     */
    private void syncKgService(
            String erpOutboundContractId, String erpProductId, String erpCustomerId,
            BigDecimal quantityGwh, BigDecimal unitPriceUsdKwh, BigDecimal penaltyPct,
            BigDecimal lineStopChargeUsd, BigDecimal lineStopChargeKrw,
            Integer deliveryLeadTimeDays, String contractLanguage) {
        KgOutboundContractFields contract = new KgOutboundContractFields(
                erpOutboundContractId, erpProductId, erpCustomerId, "LG에너지솔루션",
                quantityGwh.doubleValue(), unitPriceUsdKwh.doubleValue(), penaltyPct.doubleValue(),
                lineStopChargeUsd == null ? null : lineStopChargeUsd.doubleValue(),
                lineStopChargeKrw == null ? null : lineStopChargeKrw.doubleValue(),
                deliveryLeadTimeDays, contractLanguage);
        try {
            kgServiceRestClient.post()
                    .uri("/admin/append_outbound_contract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new KgAppendOutboundContractRequest(contract))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exception) {
            // KG 반영은 부가 기능 — 실패해도 계약/문서 생성 자체를 막지 않는다(인바운드와 동일 정책).
            log.warn(
                    "kg_service 아웃바운드 동기화 실패 — CSV(로컬 사본)는 갱신됐으나 KG 그래프는 아직 이전 상태입니다",
                    exception);
        }
    }

    private void appendOutboundContractsCsvRow(
            String erpOutboundContractId, String erpProductId, String erpCustomerId,
            BigDecimal quantityGwh, BigDecimal unitPriceUsdKwh, BigDecimal penaltyPct,
            BigDecimal lineStopChargeUsd, BigDecimal lineStopChargeKrw,
            Integer deliveryLeadTimeDays, String contractLanguage) {
        if (outboundErpSeedDirectory == null) {
            log.warn("app.erp.outbound-seed.directory 미설정 — outbound_contracts.csv 동기화를 건너뜁니다");
            return;
        }
        String row = String.join(",",
                erpOutboundContractId, erpProductId, erpCustomerId, "LG에너지솔루션",
                quantityGwh.toString(), unitPriceUsdKwh.toString(), penaltyPct.toString(),
                lineStopChargeUsd == null ? "" : lineStopChargeUsd.toString(),
                lineStopChargeKrw == null ? "" : lineStopChargeKrw.toString(),
                String.valueOf(deliveryLeadTimeDays), contractLanguage);
        appendLine(outboundErpSeedDirectory.resolve("outbound_contracts.csv"), row);
        incrementManifestRowCount("outbound_contracts.csv");
    }

    /**
     * 인바운드와 같은 이유(ErpSeedConfig가 부팅 시 row_count 불일치를 크래시로 처리) — CSV에
     * append할 때마다 00_outbound_manifest.csv도 같이 갱신해야 다음 재기동 때 안 죽는다.
     */
    private void incrementManifestRowCount(String targetFileName) {
        Path manifestPath = outboundErpSeedDirectory.resolve("00_outbound_manifest.csv");
        try {
            java.util.List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
            java.util.List<String> updated = new java.util.ArrayList<>(lines.size());
            for (String line : lines) {
                String[] fields = line.split(",", -1);
                if (fields.length >= 4 && fields[1].equals(targetFileName)) {
                    int currentCount = Integer.parseInt(fields[3].trim());
                    fields[3] = String.valueOf(currentCount + 1);
                    updated.add(String.join(",", fields));
                } else {
                    updated.add(line);
                }
            }
            Files.write(manifestPath, updated, StandardCharsets.UTF_8);
        } catch (IOException | NumberFormatException exception) {
            log.warn(
                    "00_outbound_manifest.csv의 {} row_count 갱신 실패 — 다음 Spring 재기동 시 "
                            + "row count mismatch로 기동에 실패할 수 있음, 수동 확인 필요",
                    targetFileName, exception);
        }
    }

    private void appendLine(Path csvPath, String line) {
        try {
            Files.writeString(
                    csvPath, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            log.warn("CSV 동기화 실패: {} — 계약/문서 생성은 이미 완료됐습니다", csvPath, exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static long requireFk(Optional<Long> resolved, ErrorCode notFound) {
        return resolved.orElseThrow(() -> new BusinessException(notFound)).longValue();
    }

    private record ExtractTextFastApiResponse(
            boolean success, ExtractTextData data, OffsetDateTime timestamp) {}

    private record ExtractTextData(@JsonProperty("text") String text) {}

    // kg_service의 AppendOutboundContractRequest/OutboundContractAppendFields
    // (kg_service/main.py)와 필드 이름을 그대로 맞춘 snake_case DTO.
    private record KgAppendOutboundContractRequest(
            @JsonProperty("outbound_contract") KgOutboundContractFields outboundContract) {}

    private record KgOutboundContractFields(
            @JsonProperty("erp_outbound_contract_id") String erpOutboundContractId,
            @JsonProperty("erp_product_id") String erpProductId,
            @JsonProperty("erp_customer_id") String erpCustomerId,
            @JsonProperty("seller") String seller,
            @JsonProperty("quantity_gwh") double quantityGwh,
            @JsonProperty("unit_price_usd_kwh") double unitPriceUsdKwh,
            @JsonProperty("penalty_pct") double penaltyPct,
            @JsonProperty("line_stop_charge_usd") Double lineStopChargeUsd,
            @JsonProperty("line_stop_charge_krw") Double lineStopChargeKrw,
            @JsonProperty("delivery_lead_time_days") int deliveryLeadTimeDays,
            @JsonProperty("contract_language") String contractLanguage) {}

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String fileName;

        private NamedByteArrayResource(byte[] content, String fileName) {
            super(content);
            this.fileName = fileName;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }
}

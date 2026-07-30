package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ContractUploadDto;
import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.dto.ErpAdminDto;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 계약서 업로드 → CTR-XXX 자동 발급 + ERP/RAG/KG 동기화 (계획서 "부속 작업 2").
 *
 * 미리보기(DB 안 씀) → 확정(실제 생성) 2단계로 나눈다. 확정 단계는 대부분 기존 서비스를 그대로
 * 재사용한다 — {@link ErpAdminService}(계약/공급관계 upsert), {@link DocumentService}(RAG 적재).
 * 이 클래스가 새로 하는 일은: 원문 텍스트 추출 요청(FastAPI), 정규식 필드 추출({@link
 * ContractFieldExtractor}), CTR-XXX 채번, CSV 동기화, KG reload 트리거뿐이다.
 */
@Service
public class ContractUploadService {
    private static final Logger log = LoggerFactory.getLogger(ContractUploadService.class);

    private final RestClient fastApiRestClient;
    private final RestClient kgServiceRestClient;
    private final ErpRepository repository;
    private final ErpAdminService erpAdminService;
    private final DocumentService documentService;
    private final Path erpSeedDirectory;

    public ContractUploadService(
            RestClient fastApiRestClient,
            RestClient kgServiceRestClient,
            ErpRepository repository,
            ErpAdminService erpAdminService,
            DocumentService documentService,
            @Value("${app.erp.seed.directory:}") String erpSeedDirectory) {
        this.fastApiRestClient = fastApiRestClient;
        this.kgServiceRestClient = kgServiceRestClient;
        this.repository = repository;
        this.erpAdminService = erpAdminService;
        this.documentService = documentService;
        this.erpSeedDirectory = erpSeedDirectory == null || erpSeedDirectory.isBlank()
                ? null : Path.of(erpSeedDirectory).toAbsolutePath().normalize();
    }

    /** 1단계: 파일에서 텍스트를 뽑아 계약 필드를 미리 채워 보여준다. DB에는 아무것도 안 쓴다. */
    public ContractUploadDto.PreviewResponse preview(
            MultipartFile file, String erpSupplierId, String erpMaterialId) {
        long supplierId = requireFk(repository.resolveSupplierId(erpSupplierId), ErrorCode.ERP_SUPPLIER_NOT_FOUND);
        long materialId = requireFk(repository.resolveMaterialId(erpMaterialId), ErrorCode.ERP_MATERIAL_NOT_FOUND);

        String text = extractText(file);
        ContractFieldExtractor.ExtractedFields fields = ContractFieldExtractor.extract(text);

        Optional<ErpRepository.ContractRef> existing =
                repository.findContractForSupplierMaterial(supplierId, materialId);
        String expectedNewId = existing.isPresent() ? null : nextContractId();

        return new ContractUploadDto.PreviewResponse(
                erpSupplierId, erpMaterialId,
                existing.map(ErpRepository.ContractRef::erpContractId).orElse(null),
                expectedNewId,
                fields.contractNumber(), fields.contractName(),
                fields.effectiveDate(), fields.expirationDate());
    }

    /**
     * 2단계: 미리보기에서 확인/수정한 값으로 실제 생성한다. (공급사,자재) 조합에 계약이 이미
     * 있으면 새로 안 만들고 그 계약에 문서만 추가한다.
     *
     * {@code contractNumber}/{@code contractName}/{@code effectiveDate}/{@code expirationDate}는
     * 전부 선택 파라미터다 — 새 계약인데 이 중 뭔가 비어 있으면, 호출자가 {@code /preview}를
     * 먼저 안 거쳤더라도 이 메서드가 직접 파일에서 정규식으로 추출해서 채운다({@link
     * ContractFieldExtractor}). 즉 "공급사+자재+파일만 주면 끝"이 되는 게 기본 경로이고,
     * {@code /preview}는 저장 전에 추출 결과를 미리 보여주고 싶을 때만 쓰는 선택 단계다.
     */
    public ContractUploadDto.ConfirmResponse confirm(
            MultipartFile file, String erpSupplierId, String erpMaterialId,
            String contractNumber, String contractName,
            LocalDate effectiveDate, LocalDate expirationDate) {
        long supplierId = requireFk(repository.resolveSupplierId(erpSupplierId), ErrorCode.ERP_SUPPLIER_NOT_FOUND);
        long materialId = requireFk(repository.resolveMaterialId(erpMaterialId), ErrorCode.ERP_MATERIAL_NOT_FOUND);

        Optional<ErpRepository.ContractRef> existing =
                repository.findContractForSupplierMaterial(supplierId, materialId);

        long contractId;
        String erpContractId;
        boolean created;

        if (existing.isPresent()) {
            contractId = existing.get().contractId();
            erpContractId = existing.get().erpContractId();
            created = false;
        } else {
            if (isBlank(contractNumber) || isBlank(contractName) || effectiveDate == null) {
                ContractFieldExtractor.ExtractedFields extracted =
                        ContractFieldExtractor.extract(extractText(file));
                if (isBlank(contractNumber)) {
                    contractNumber = extracted.contractNumber();
                }
                if (isBlank(contractName)) {
                    contractName = extracted.contractName();
                }
                if (effectiveDate == null) {
                    effectiveDate = extracted.effectiveDate();
                }
                if (expirationDate == null) {
                    expirationDate = extracted.expirationDate();
                }
            }
            if (isBlank(contractNumber) || isBlank(contractName) || effectiveDate == null) {
                throw new DocumentUploadException(
                        "CONTRACT_FIELDS_REQUIRED",
                        "계약번호/계약명/발효일을 파일에서 자동으로 찾지 못했습니다. 직접 입력해주세요.");
            }
            // 미리보기 때 보여준 CTR-XXX는 예상값일 뿐 — 그 사이 다른 업로드가 먼저 확정됐을 수
            // 있으니 확정 시점에 다시 계산한다(계획서의 "채번 경합" 단순화 처리).
            erpContractId = nextContractId();
            String documentPath = "contracts/" + erpContractId + "/original";

            ErpAdminDto.UpsertResponse contractResponse = erpAdminService.upsertContract(
                    new ErpAdminDto.ContractUpsertRequest(
                            erpContractId, contractNumber, erpSupplierId, erpMaterialId, contractName,
                            "ACTIVE", effectiveDate, expirationDate,
                            "PENDING_UPLOAD", "USER_UPLOAD", documentPath,
                            "PRIMARY", "APPROVED", OffsetDateTime.now()));
            contractId = contractResponse.internalId();
            created = true;

            String erpSupplierMaterialId = nextSupplierMaterialId();
            erpAdminService.upsertSupplierMaterial(new ErpAdminDto.SupplierMaterialUpsertRequest(
                    erpSupplierMaterialId, erpSupplierId, erpMaterialId, erpContractId,
                    BigDecimal.ONE, 30, BigDecimal.ZERO, "APPROVED", 1, false, LocalDate.now(), null));

            OffsetDateTime now = OffsetDateTime.now();
            appendContractsCsvRow(
                    erpContractId, contractNumber, erpSupplierId, erpMaterialId, contractName,
                    effectiveDate, expirationDate, documentPath, now);
            appendSupplierMaterialsCsvRow(
                    erpSupplierMaterialId, erpSupplierId, erpMaterialId, erpContractId);
            syncKgService(
                    erpContractId, contractNumber, erpSupplierId, erpMaterialId, contractName,
                    effectiveDate, expirationDate, documentPath, now,
                    erpSupplierMaterialId);
        }

        DocumentDto.UploadResponse uploadResponse =
                documentService.upload(file, contractId, supplierId, materialId, "CONTRACT");

        return new ContractUploadDto.ConfirmResponse(
                erpContractId, created, uploadResponse.documentId(), uploadResponse.processingStatus());
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
            // 계속 진행하고 사용자가 직접 입력하게 한다(계획서 "알려진 단순화" 참고).
            log.warn("계약서 텍스트 추출 실패, 필드 자동 채움 없이 진행합니다", exception);
            return "";
        }
    }

    private String nextContractId() {
        int next = repository.findMaxContractSequence().map(n -> n + 1).orElse(1);
        return "CTR-%03d".formatted(next);
    }

    private String nextSupplierMaterialId() {
        int next = repository.findMaxSupplierMaterialSequence().map(n -> n + 1).orElse(1);
        return "SM-%03d".formatted(next);
    }

    /**
     * kg_service(호스트에서 도는 일반 Python 프로세스)에게 같은 계약/공급관계 정보를 보내
     * 자기 쪽 CSV(ERP_DIR)에도 append하고 그래프를 다시 만들게 한다.
     *
     * 이걸 굳이 Spring이 직접 두 번째 파일에 쓰지 않고 kg_service를 거치는 이유: 이 컨테이너는
     * Docker(WSL2) 안에서 도는데, kg_service의 진짜 소스(빅프로젝트/backend/data/ERP_data/
     * spring-csv)는 Google Drive 가상 드라이브(G:) 안에 있어서 Docker가 그 경로를 마운트해도
     * 빈 폴더로 보인다(오늘 직접 확인함) — 반면 kg_service는 일반 Python 파일 I/O라 그 드라이브를
     * 문제없이 읽고 쓴다. 그래서 "두 번째 CSV에 쓰는 일"은 Spring이 아니라 kg_service가 한다.
     */
    private void syncKgService(
            String erpContractId, String contractNumber, String erpSupplierId, String erpMaterialId,
            String contractName, LocalDate effectiveDate, LocalDate expirationDate, String documentPath,
            OffsetDateTime now, String erpSupplierMaterialId) {
        KgContractFields contract = new KgContractFields(
                erpContractId, contractNumber, erpSupplierId, erpMaterialId, contractName,
                "ACTIVE", effectiveDate.toString(), expirationDate == null ? null : expirationDate.toString(),
                "PENDING_UPLOAD", "USER_UPLOAD", documentPath, "PRIMARY", "APPROVED",
                now.toString(), now.toString());
        KgSupplierMaterialFields supplierMaterial = new KgSupplierMaterialFields(
                erpSupplierMaterialId, erpSupplierId, erpMaterialId,
                BigDecimal.ONE, 30, BigDecimal.ZERO, "APPROVED", 1, false,
                LocalDate.now().toString(), null);
        try {
            kgServiceRestClient.post()
                    .uri("/admin/append_contract")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new KgAppendContractRequest(contract, supplierMaterial))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exception) {
            // KG 반영은 부가 기능 — 실패해도 계약/문서 생성 자체를 막지 않는다. CSV(로컬 사본)는
            // 이미 갱신됐으니 나중에 kg_service 쪽에 수동으로 반영할 수 있다.
            log.warn("kg_service 동기화 실패 — CSV(로컬 사본)는 갱신됐으나 KG 그래프는 아직 이전 상태입니다", exception);
        }
    }

    private void appendContractsCsvRow(
            String erpContractId, String contractNumber, String erpSupplierId, String erpMaterialId,
            String contractName, LocalDate effectiveDate, LocalDate expirationDate, String documentPath,
            OffsetDateTime now) {
        if (erpSeedDirectory == null) {
            log.warn("app.erp.seed.directory 미설정 — 04_contracts.csv 동기화를 건너뜁니다");
            return;
        }
        String nowStr = now.toString();
        String row = String.join(",",
                erpContractId, csv(contractNumber), erpSupplierId, erpMaterialId, csv(contractName),
                "ACTIVE", String.valueOf(effectiveDate),
                expirationDate == null ? "" : expirationDate.toString(),
                "PENDING_UPLOAD", "USER_UPLOAD", csv(documentPath),
                "PRIMARY", "APPROVED", nowStr, nowStr);
        appendLine(erpSeedDirectory.resolve("04_contracts.csv"), row);
        incrementManifestRowCount("04_contracts.csv");
    }

    private void appendSupplierMaterialsCsvRow(
            String erpSupplierMaterialId, String erpSupplierId, String erpMaterialId, String erpContractId) {
        if (erpSeedDirectory == null) {
            return;
        }
        String row = String.join(",",
                erpSupplierMaterialId, erpSupplierId, erpMaterialId,
                "1.0", "30", "0", "APPROVED", "1", "false",
                LocalDate.now().toString(), "", erpContractId);
        appendLine(erpSeedDirectory.resolve("05_supplier_materials.csv"), row);
        incrementManifestRowCount("05_supplier_materials.csv");
    }

    /**
     * {@code ErpSeedConfig}가 시작할 때 00_manifest.csv의 row_count와 실제 CSV 행 수가 정확히
     * 일치하는지 검증하고 안 맞으면 기동을 막는다(ErpSeedConfig.readManifest) — CSV에 새 행을
     * append만 하고 이걸 안 늘리면 다음 재기동 때부터 계속 죽는다(실제로 겪은 버그, 데이터
     * 자체는 멀쩡한데도 크래시 루프에 빠짐). 그래서 append할 때마다 반드시 같이 갱신한다.
     */
    private void incrementManifestRowCount(String targetFileName) {
        Path manifestPath = erpSeedDirectory.resolve("00_manifest.csv");
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
                    "00_manifest.csv의 {} row_count 갱신 실패 — 다음 Spring 재기동 시 "
                            + "ErpSeedConfig가 row count mismatch로 기동에 실패할 수 있음, 수동 확인 필요",
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

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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

    // kg_service의 AppendContractRequest/ContractAppendFields/SupplierMaterialAppendFields
    // (kg_service/main.py)와 필드 이름을 그대로 맞춘 snake_case DTO.
    private record KgAppendContractRequest(
            KgContractFields contract,
            @JsonProperty("supplier_material") KgSupplierMaterialFields supplierMaterial) {}

    private record KgContractFields(
            @JsonProperty("erp_contract_id") String erpContractId,
            @JsonProperty("contract_number") String contractNumber,
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("contract_name") String contractName,
            @JsonProperty("contract_status") String contractStatus,
            @JsonProperty("effective_date") String effectiveDate,
            @JsonProperty("expiration_date") String expirationDate,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("document_source") String documentSource,
            @JsonProperty("document_path") String documentPath,
            @JsonProperty("contract_role") String contractRole,
            @JsonProperty("supplier_approval_status") String supplierApprovalStatus,
            @JsonProperty("indexed_at") String indexedAt,
            @JsonProperty("updated_at") String updatedAt) {}

    private record KgSupplierMaterialFields(
            @JsonProperty("erp_supplier_material_id") String erpSupplierMaterialId,
            @JsonProperty("erp_supplier_id") String erpSupplierId,
            @JsonProperty("erp_material_id") String erpMaterialId,
            @JsonProperty("supply_share_ratio") BigDecimal supplyShareRatio,
            @JsonProperty("lead_time_days") int leadTimeDays,
            @JsonProperty("minimum_order_quantity") BigDecimal minimumOrderQuantity,
            @JsonProperty("approved_status") String approvedStatus,
            @JsonProperty("priority_rank") int priorityRank,
            @JsonProperty("is_alternative") boolean isAlternative,
            @JsonProperty("valid_from") String validFrom,
            @JsonProperty("valid_to") String validTo) {}

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

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
     * 2단계: 사용자가 미리보기 모달에서 확인/수정한 값으로 실제 생성한다.
     * (공급사,자재) 조합에 계약이 이미 있으면 새로 안 만들고 그 계약에 문서만 추가한다.
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
                throw new DocumentUploadException(
                        "CONTRACT_FIELDS_REQUIRED", "계약번호/계약명/발효일은 필수입니다.");
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

            appendContractsCsvRow(
                    erpContractId, contractNumber, erpSupplierId, erpMaterialId, contractName,
                    effectiveDate, expirationDate, documentPath);
            appendSupplierMaterialsCsvRow(
                    erpSupplierMaterialId, erpSupplierId, erpMaterialId, erpContractId);
            reloadKg();
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

    private void reloadKg() {
        try {
            kgServiceRestClient.post().uri("/admin/reload").retrieve().toBodilessEntity();
        } catch (Exception exception) {
            // KG 반영은 부가 기능 — 실패해도 계약/문서 생성 자체를 막지 않는다. 다음
            // 수동 재구축이나 서비스 재시작 시 CSV는 이미 갱신돼 있으니 알아서 반영된다.
            log.warn("kg_service reload 호출 실패 — CSV는 갱신됐으나 그래프는 아직 이전 상태입니다", exception);
        }
    }

    private void appendContractsCsvRow(
            String erpContractId, String contractNumber, String erpSupplierId, String erpMaterialId,
            String contractName, LocalDate effectiveDate, LocalDate expirationDate, String documentPath) {
        if (erpSeedDirectory == null) {
            log.warn("app.erp.seed.directory 미설정 — 04_contracts.csv 동기화를 건너뜁니다");
            return;
        }
        String now = OffsetDateTime.now().toString();
        String row = String.join(",",
                erpContractId, csv(contractNumber), erpSupplierId, erpMaterialId, csv(contractName),
                "ACTIVE", String.valueOf(effectiveDate),
                expirationDate == null ? "" : expirationDate.toString(),
                "PENDING_UPLOAD", "USER_UPLOAD", csv(documentPath),
                "PRIMARY", "APPROVED", now, now);
        appendLine(erpSeedDirectory.resolve("04_contracts.csv"), row);
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

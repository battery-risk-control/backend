package com.example.batteryrisk.config;

import com.example.batteryrisk.dto.OutboundDocumentDto;
import com.example.batteryrisk.service.OutboundDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 아웃바운드(LG에너지솔루션→완성차/ESS 고객사) 계약서 RAG 자동 적재기.
 *
 * <p>{@link RagSeedConfig}(인바운드)와 완전히 독립된 별도 러너다. 존재 이유: 인바운드
 * RagSeedConfig는 {@code contracts} 테이블(supplier_id/material_id 기준)만 조회하므로
 * outbound_contracts(product_id/customer_id 기준)는 원천적으로 못 찾는다 — 계약서 파일
 * 자체는 이미 만들어져 있었는데도(rag_dataset/erp_aligned) 이 적재기가 없어서 Docker
 * 자동 기동 시 완성차 계약서는 한 번도 ChromaDB에 안 들어가고 있었다(2026-07-31 발견).
 * 그 결과 "원자재 공급 문제로 생산 지연 → 완성차업체 배상 조건" 같은 질의에 Contract
 * Agent가 실제 조항을 찾지 못하는 상태였다.
 *
 * <p>흐름은 RagSeedConfig와 동일하되 매핑 테이블과 업로드 서비스만 아웃바운드용으로 바꾼다.
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.outbound-seed.enabled", havingValue = "true")
public class OutboundRagSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(OutboundRagSeedConfig.class);
    private static final int FASTAPI_WAIT_ATTEMPTS = 20;
    private static final long FASTAPI_WAIT_INTERVAL_MS = 3_000L;

    @Bean
    @Order(21)   // OutboundErpSeedConfig 다음에 실행 — 아웃바운드 계약 매핑이 준비된 뒤 적재
    ApplicationRunner outboundRagSeedRunner(
            OutboundDocumentService outboundDocumentService,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            RestClient fastApiRestClient,
            @Value("${app.rag.outbound-seed.directory:}") String configuredDirectory,
            @Value("${app.rag.outbound-seed.document-type:CONTRACT}") String documentType) {
        return args -> {
            if (configuredDirectory == null || configuredDirectory.isBlank()) {
                throw new IllegalStateException(
                        "OUTBOUND_RAG_SEED_DIRECTORY is required when OUTBOUND_RAG_SEED_ENABLED=true");
            }
            Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Outbound RAG seed directory does not exist: " + root);
            }

            // erp_outbound_contract_id → [outbound_contract_id, product_id, customer_id]
            Map<String, long[]> contractMap = new LinkedHashMap<>();
            List<Map<String, Object>> contractRows = jdbc.queryForList(
                    "SELECT erp_outbound_contract_id, outbound_contract_id, product_id, customer_id "
                            + "FROM outbound_contracts "
                            + "WHERE erp_outbound_contract_id IS NOT NULL AND erp_outbound_contract_id <> '' "
                            + "AND product_id IS NOT NULL AND customer_id IS NOT NULL");
            for (Map<String, Object> row : contractRows) {
                contractMap.put(
                        (String) row.get("erp_outbound_contract_id"),
                        new long[] {
                                ((Number) row.get("outbound_contract_id")).longValue(),
                                ((Number) row.get("product_id")).longValue(),
                                ((Number) row.get("customer_id")).longValue()});
            }
            if (contractMap.isEmpty()) {
                log.warn("Outbound RAG seed: outbound_contracts 매핑이 비어 있습니다. "
                        + "아웃바운드 ERP 시드(OUTBOUND_ERP_SEED_ENABLED)를 먼저 수행하세요. 적재를 건너뜁니다.");
                return;
            }

            List<Path> files = listSeedFiles(root);
            if (files.isEmpty()) {
                log.warn("Outbound RAG seed: {} 에 적재할 파일(.txt/.pdf)이 없습니다.", root);
                return;
            }

            if (!waitForFastApi(fastApiRestClient)) {
                log.error("Outbound RAG seed: FastAPI가 준비되지 않아 적재를 건너뜁니다. 컨테이너 상태를 확인 후 재시작하세요.");
                return;
            }

            int ok = 0;
            int skipped = 0;
            int failed = 0;
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String erpOutboundContractId = extractContractId(fileName);
                long[] ids = contractMap.get(erpOutboundContractId);
                if (ids == null) {
                    log.warn("Outbound RAG seed: 계약 매핑 없음 (erp_outbound_contract_id={}, 파일={}) — 건너뜀",
                            erpOutboundContractId, fileName);
                    skipped++;
                    continue;
                }
                try {
                    byte[] content = Files.readAllBytes(file);
                    MultipartFile multipartFile = new SeedMultipartFile(fileName, mimeTypeFor(fileName), content);
                    OutboundDocumentDto.UploadResponse response = outboundDocumentService.upload(
                            multipartFile, ids[0], ids[1], ids[2], documentType);
                    log.info("Outbound RAG seed: {} → outbound_contract_id={} chunks={} dup={} embed={}",
                            erpOutboundContractId, ids[0], response.chunkCount(),
                            response.duplicate(), response.embeddingType());
                    ok++;
                } catch (Exception exception) {
                    log.error("Outbound RAG seed 실패 (파일={}): {}", fileName, exception.getMessage());
                    failed++;
                }
            }
            log.info("Outbound RAG seed 완료: 성공 {} · 건너뜀 {} · 실패 {} (dir={})", ok, skipped, failed, root);
        };
    }

    /** 파일명 접두사에서 erp_outbound_contract_id 추출: "CTR-OUT-001_....txt" → "CTR-OUT-001". */
    private static String extractContractId(String fileName) {
        int underscore = fileName.indexOf('_');
        if (underscore > 0) {
            return fileName.substring(0, underscore);
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static List<Path> listSeedFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".txt") || name.endsWith(".pdf");
                    })
                    .sorted()
                    .forEach(files::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Outbound RAG seed 디렉토리 목록 조회 실패: " + root, exception);
        }
        return files;
    }

    private static String mimeTypeFor(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "text/plain";
    }

    /** FastAPI /health가 응답할 때까지 잠시 대기(임베딩 호출 전 준비 확인). */
    private static boolean waitForFastApi(RestClient fastApiRestClient) {
        for (int attempt = 1; attempt <= FASTAPI_WAIT_ATTEMPTS; attempt++) {
            try {
                fastApiRestClient.get().uri("/health").retrieve().body(String.class);
                return true;
            } catch (Exception exception) {
                if (attempt == 1) {
                    log.info("Outbound RAG seed: FastAPI 준비 대기 중...");
                }
                try {
                    Thread.sleep(FASTAPI_WAIT_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** 시드 전용 최소 MultipartFile 구현 (디스크 바이트를 OutboundDocumentService.upload에 넘기기 위함). */
    private static final class SeedMultipartFile implements MultipartFile {
        private final String fileName;
        private final String contentType;
        private final byte[] content;

        private SeedMultipartFile(String fileName, String contentType, byte[] content) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.write(dest.toPath(), content);
        }
    }
}

package com.example.batteryrisk.config;

import com.example.batteryrisk.dto.DocumentDto;
import com.example.batteryrisk.service.DocumentService;
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
 * RAG 계약서 자동 적재기 (ErpSeedConfig의 RAG 버전).
 *
 * <p>app.rag.seed.enabled=true일 때, 앱 시작 시 지정 디렉토리의 계약서 파일을 읽어
 * ChromaDB(RAG)에 적재한다. ERP 시드(ErpSeedConfig)가 CSV→PostgreSQL을 하는 것과 대칭으로,
 * 이 적재기는 계약서 파일→ChromaDB를 담당한다.
 *
 * <p>흐름:
 * <ol>
 *   <li>PostgreSQL contracts에서 {@code erp_contract_id → (contract_id, supplier_id, material_id)} 매핑을 읽는다.</li>
 *   <li>디렉토리의 각 파일에서 파일명 접두사(예: {@code CTR-010_...txt} → {@code CTR-010})로 계약을 특정한다.</li>
 *   <li>{@link DocumentService#upload}로 업로드한다(서버가 청킹·임베딩·Chroma 적재를 수행).</li>
 * </ol>
 *
 * <p>재실행 안전: DocumentService가 (contract_id, content_hash)로 중복을 걸러 이미 적재된 파일은 건너뛴다.
 * <p>FastAPI가 떠 있어야 임베딩이 되므로, 시작 시 FastAPI /health를 잠시 대기한 뒤 적재한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.seed.enabled", havingValue = "true")
public class RagSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(RagSeedConfig.class);
    private static final int FASTAPI_WAIT_ATTEMPTS = 20;
    private static final long FASTAPI_WAIT_INTERVAL_MS = 3_000L;

    @Bean
    @Order(20)   // ErpSeedConfig(@Order 10) 다음에 실행 — 계약 매핑이 준비된 뒤 적재
    ApplicationRunner ragSeedRunner(
            DocumentService documentService,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            RestClient fastApiRestClient,
            @Value("${app.rag.seed.directory:}") String configuredDirectory,
            @Value("${app.rag.seed.document-type:CONTRACT}") String documentType) {
        return args -> {
            if (configuredDirectory == null || configuredDirectory.isBlank()) {
                throw new IllegalStateException(
                        "RAG_SEED_DIRECTORY is required when RAG_SEED_ENABLED=true");
            }
            Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("RAG seed directory does not exist: " + root);
            }

            // erp_contract_id → [contract_id, supplier_id, material_id]
            Map<String, long[]> contractMap = new LinkedHashMap<>();
            List<Map<String, Object>> contractRows = jdbc.queryForList(
                    "SELECT erp_contract_id, contract_id, supplier_id, material_id FROM contracts "
                            + "WHERE erp_contract_id IS NOT NULL AND erp_contract_id <> '' "
                            + "AND supplier_id IS NOT NULL AND material_id IS NOT NULL");
            for (Map<String, Object> row : contractRows) {
                contractMap.put(
                        (String) row.get("erp_contract_id"),
                        new long[] {
                                ((Number) row.get("contract_id")).longValue(),
                                ((Number) row.get("supplier_id")).longValue(),
                                ((Number) row.get("material_id")).longValue()});
            }
            if (contractMap.isEmpty()) {
                log.warn("RAG seed: contracts 매핑이 비어 있습니다. ERP 시드(ERP_SEED_ENABLED)를 먼저 수행하세요. 적재를 건너뜁니다.");
                return;
            }

            List<Path> files = listSeedFiles(root);
            if (files.isEmpty()) {
                log.warn("RAG seed: {} 에 적재할 파일(.txt/.pdf)이 없습니다.", root);
                return;
            }

            if (!waitForFastApi(fastApiRestClient)) {
                log.error("RAG seed: FastAPI가 준비되지 않아 적재를 건너뜁니다. 컨테이너 상태를 확인 후 재시작하세요.");
                return;
            }

            int ok = 0;
            int skipped = 0;
            int failed = 0;
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String erpContractId = extractContractId(fileName);
                long[] ids = contractMap.get(erpContractId);
                if (ids == null) {
                    log.warn("RAG seed: 계약 매핑 없음 (erp_contract_id={}, 파일={}) — 건너뜀", erpContractId, fileName);
                    skipped++;
                    continue;
                }
                try {
                    byte[] content = Files.readAllBytes(file);
                    MultipartFile multipartFile = new SeedMultipartFile(fileName, mimeTypeFor(fileName), content);
                    DocumentDto.UploadResponse response = documentService.upload(
                            multipartFile, ids[0], ids[1], ids[2], documentType);
                    log.info("RAG seed: {} → contract_id={} chunks={} dup={} embed={}",
                            erpContractId, ids[0], response.chunkCount(), response.duplicate(), response.embeddingType());
                    ok++;
                } catch (Exception exception) {
                    log.error("RAG seed 실패 (파일={}): {}", fileName, exception.getMessage());
                    failed++;
                }
            }
            log.info("RAG seed 완료: 성공 {} · 건너뜀 {} · 실패 {} (dir={})", ok, skipped, failed, root);
        };
    }

    /** 파일명 접두사에서 erp_contract_id 추출: "CTR-010_EX-10_....txt" → "CTR-010". '_'가 없으면 확장자만 제거. */
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
            throw new IllegalStateException("RAG seed 디렉토리 목록 조회 실패: " + root, exception);
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
                    log.info("RAG seed: FastAPI 준비 대기 중...");
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

    /** 시드 전용 최소 MultipartFile 구현 (디스크 바이트를 DocumentService.upload에 넘기기 위함). */
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

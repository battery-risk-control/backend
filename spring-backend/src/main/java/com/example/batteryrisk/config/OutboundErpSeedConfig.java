package com.example.batteryrisk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아웃바운드(LG에너지솔루션 -> 완성차/ESS 고객사) mock CSV(products/product_composition/
 * customers/outbound_contracts)를 부팅 시 Postgres에 적재한다. {@link ErpSeedConfig}(인바운드,
 * 10개 파일 하드코딩)와 완전히 독립된 별도 러너 — 자체 프로퍼티/디렉터리/매니페스트를 쓰고,
 * 인바운드 로더의 "정확히 10개 파일" 검증은 전혀 건드리지 않는다. CSV 파싱/인코딩 폴백 헬퍼는
 * {@link ErpSeedConfig}의 package-private 메서드를 그대로 재사용한다(로직 중복 없음).
 */
@Configuration
@ConditionalOnProperty(name = "app.erp.outbound-seed.enabled", havingValue = "true")
public class OutboundErpSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(OutboundErpSeedConfig.class);

    @Bean
    @Order(11)   // 인바운드 ERP 시드(@Order(10)) 다음 — 서로 FK 의존성은 없지만 순서를 명확히 한다
    ApplicationRunner outboundErpCsvSeedRunner(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${app.erp.outbound-seed.directory:}") String configuredDirectory) {
        return args -> {
            if (configuredDirectory == null || configuredDirectory.isBlank()) {
                throw new IllegalStateException(
                        "OUTBOUND_ERP_SEED_DIRECTORY is required when OUTBOUND_ERP_SEED_ENABLED=true");
            }

            Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Outbound ERP seed directory does not exist: " + root);
            }

            List<OutboundManifestEntry> manifest = readManifest(root);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> loadAll(jdbc, root, manifest));
            log.info("Outbound ERP CSV seed completed: {} files from {}", manifest.size(), root);
        };
    }

    private static void loadAll(JdbcTemplate jdbc, Path root, List<OutboundManifestEntry> manifest) {
        Map<String, Long> productIds = new LinkedHashMap<>();
        Map<String, Long> customerIds = new LinkedHashMap<>();

        for (OutboundManifestEntry entry : manifest) {
            ErpSeedConfig.CsvTable table = ErpSeedConfig.readCsv(ErpSeedConfig.resolveInside(root, entry.fileName()));
            if (table.rows().size() != entry.rowCount()) {
                throw new IllegalStateException(
                        entry.fileName() + " row count mismatch: expected " + entry.rowCount()
                                + ", actual " + table.rows().size());
            }

            switch (entry.fileName()) {
                case "products.csv" -> {
                    seedProducts(jdbc, table.rows());
                    productIds.putAll(ErpSeedConfig.readIdMap(jdbc,
                            "SELECT erp_product_id, product_id FROM products"));
                }
                case "product_composition.csv" -> {
                    ErpSeedConfig.requireMaps(entry.fileName(), productIds);
                    seedProductCompositions(jdbc, table.rows(), productIds);
                }
                case "customers.csv" -> {
                    seedCustomers(jdbc, table.rows());
                    customerIds.putAll(ErpSeedConfig.readIdMap(jdbc,
                            "SELECT erp_customer_id, customer_id FROM customers"));
                }
                case "outbound_contracts.csv" -> {
                    ErpSeedConfig.requireMaps(entry.fileName(), productIds, customerIds);
                    seedOutboundContracts(jdbc, table.rows(), productIds, customerIds);
                }
                default -> throw new IllegalStateException(
                        "Unsupported outbound ERP seed file in manifest: " + entry.fileName());
            }
        }
    }

    private static void seedProducts(JdbcTemplate jdbc, List<Map<String, String>> rows) {
        String sql = """
                INSERT INTO products (
                    erp_product_id, name_en, name_kr, product_line, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (erp_product_id) DO UPDATE SET
                    name_en = EXCLUDED.name_en,
                    name_kr = EXCLUDED.name_kr,
                    product_line = EXCLUDED.product_line,
                    updated_at = CURRENT_TIMESTAMP
                """;
        rows.forEach(row -> jdbc.update(sql,
                ErpSeedConfig.required(row, "product_id"), ErpSeedConfig.required(row, "name_en"),
                ErpSeedConfig.required(row, "name_kr"), ErpSeedConfig.required(row, "product_line")));
    }

    private static void seedProductCompositions(
            JdbcTemplate jdbc, List<Map<String, String>> rows, Map<String, Long> productIds) {
        String sql = """
                INSERT INTO product_compositions (product_id, material_category)
                VALUES (?, ?)
                ON CONFLICT (product_id, material_category) DO NOTHING
                """;
        rows.forEach(row -> jdbc.update(sql,
                ErpSeedConfig.reference(productIds, row, "product_id"),
                ErpSeedConfig.required(row, "material_category")));
    }

    private static void seedCustomers(JdbcTemplate jdbc, List<Map<String, String>> rows) {
        String sql = """
                INSERT INTO customers (
                    erp_customer_id, name_en, name_kr, country_code, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (erp_customer_id) DO UPDATE SET
                    name_en = EXCLUDED.name_en,
                    name_kr = EXCLUDED.name_kr,
                    country_code = EXCLUDED.country_code,
                    updated_at = CURRENT_TIMESTAMP
                """;
        rows.forEach(row -> jdbc.update(sql,
                ErpSeedConfig.required(row, "customer_id"), ErpSeedConfig.required(row, "name_en"),
                ErpSeedConfig.required(row, "name_kr"), ErpSeedConfig.required(row, "country_code")));
    }

    private static void seedOutboundContracts(
            JdbcTemplate jdbc, List<Map<String, String>> rows,
            Map<String, Long> productIds, Map<String, Long> customerIds) {
        String sql = """
                INSERT INTO outbound_contracts (
                    erp_outbound_contract_id, product_id, customer_id, seller, quantity_gwh,
                    unit_price_usd_kwh, penalty_pct, line_stop_charge_usd, line_stop_charge_krw,
                    delivery_lead_time_days, contract_language, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (erp_outbound_contract_id) DO UPDATE SET
                    product_id = EXCLUDED.product_id,
                    customer_id = EXCLUDED.customer_id,
                    seller = EXCLUDED.seller,
                    quantity_gwh = EXCLUDED.quantity_gwh,
                    unit_price_usd_kwh = EXCLUDED.unit_price_usd_kwh,
                    penalty_pct = EXCLUDED.penalty_pct,
                    line_stop_charge_usd = EXCLUDED.line_stop_charge_usd,
                    line_stop_charge_krw = EXCLUDED.line_stop_charge_krw,
                    delivery_lead_time_days = EXCLUDED.delivery_lead_time_days,
                    contract_language = EXCLUDED.contract_language,
                    updated_at = CURRENT_TIMESTAMP
                """;
        rows.forEach(row -> jdbc.update(sql,
                ErpSeedConfig.required(row, "contract_id"),
                ErpSeedConfig.reference(productIds, row, "product_id"),
                ErpSeedConfig.reference(customerIds, row, "customer_id"),
                ErpSeedConfig.required(row, "seller"), ErpSeedConfig.decimal(row, "quantity_gwh"),
                ErpSeedConfig.decimal(row, "unit_price_usd_kwh"), ErpSeedConfig.decimal(row, "penalty_pct"),
                ErpSeedConfig.nullableDecimal(row, "line_stop_charge_usd"),
                ErpSeedConfig.nullableDecimal(row, "line_stop_charge_krw"),
                ErpSeedConfig.integer(row, "delivery_lead_time_days"),
                ErpSeedConfig.required(row, "contract_language")));
    }

    private static List<OutboundManifestEntry> readManifest(Path root) {
        ErpSeedConfig.CsvTable table =
                ErpSeedConfig.readCsv(ErpSeedConfig.resolveInside(root, "00_outbound_manifest.csv"));
        List<OutboundManifestEntry> entries = table.rows().stream()
                .map(row -> new OutboundManifestEntry(
                        ErpSeedConfig.integer(row, "load_order"), ErpSeedConfig.required(row, "file_name"),
                        ErpSeedConfig.required(row, "target_table"), ErpSeedConfig.integer(row, "row_count")))
                .sorted((left, right) -> Integer.compare(left.loadOrder(), right.loadOrder()))
                .toList();
        if (entries.size() != 4) {
            throw new IllegalStateException("Outbound ERP manifest must contain exactly 4 data files");
        }
        return entries;
    }

    private record OutboundManifestEntry(int loadOrder, String fileName, String targetTable, int rowCount) {}
}

package com.example.batteryrisk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the fixed F6 ERP mock CSV package into PostgreSQL when explicitly enabled. */
@Configuration
@ConditionalOnProperty(name = "app.erp.seed.enabled", havingValue = "true")
public class ErpSeedConfig {
    private static final Logger log = LoggerFactory.getLogger(ErpSeedConfig.class);

    @Bean
    ApplicationRunner erpCsvSeedRunner(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${app.erp.seed.directory:}") String configuredDirectory) {
        return args -> {
            if (configuredDirectory == null || configuredDirectory.isBlank()) {
                throw new IllegalStateException(
                        "ERP_SEED_DIRECTORY is required when ERP_SEED_ENABLED=true");
            }

            Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("ERP seed directory does not exist: " + root);
            }

            List<ManifestEntry> manifest = readManifest(root);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> loadAll(jdbc, root, manifest));
            log.info("F6 ERP CSV seed completed: {} files from {}", manifest.size(), root);
        };
    }

    private static void loadAll(JdbcTemplate jdbc, Path root, List<ManifestEntry> manifest) {
        Map<String, Long> materialIds = new LinkedHashMap<>();
        Map<String, Long> supplierIds = new LinkedHashMap<>();
        Map<String, Long> warehouseIds = new LinkedHashMap<>();
        Map<String, Long> contractIds = new LinkedHashMap<>();
        Map<String, Long> purchaseOrderIds = new LinkedHashMap<>();
        Map<String, Long> purchaseOrderItemIds = new LinkedHashMap<>();

        for (ManifestEntry entry : manifest) {
            CsvTable table = readCsv(resolveInside(root, entry.fileName()));
            if (table.rows().size() != entry.rowCount()) {
                throw new IllegalStateException(
                        entry.fileName() + " row count mismatch: expected " + entry.rowCount()
                                + ", actual " + table.rows().size());
            }

            switch (entry.fileName()) {
                case "01_materials.csv" -> {
                    seedMaterials(jdbc, table.rows());
                    materialIds.putAll(readIdMap(jdbc,
                            "SELECT erp_material_id, material_id FROM materials WHERE erp_material_id IS NOT NULL"));
                }
                case "02_suppliers.csv" -> {
                    seedSuppliers(jdbc, table.rows());
                    supplierIds.putAll(readIdMap(jdbc,
                            "SELECT erp_supplier_id, supplier_id FROM suppliers WHERE erp_supplier_id IS NOT NULL"));
                }
                case "03_warehouses.csv" -> {
                    seedWarehouses(jdbc, table.rows());
                    warehouseIds.putAll(readIdMap(jdbc,
                            "SELECT erp_warehouse_id, warehouse_id FROM warehouses"));
                }
                case "04_contracts.csv" -> {
                    requireMaps(entry, materialIds, supplierIds);
                    seedContracts(jdbc, table.rows(), materialIds, supplierIds);
                    contractIds.putAll(readIdMap(jdbc,
                            "SELECT erp_contract_id, contract_id FROM contracts WHERE erp_contract_id IS NOT NULL"));
                }
                case "05_supplier_materials.csv" -> {
                    requireMaps(entry, materialIds, supplierIds, contractIds);
                    seedSupplierMaterials(jdbc, table.rows(), materialIds, supplierIds, contractIds);
                }
                case "06_inventory_snapshots.csv" -> {
                    requireMaps(entry, materialIds, warehouseIds);
                    seedInventory(jdbc, table.rows(), materialIds, warehouseIds);
                }
                case "07_material_consumptions.csv" -> {
                    requireMaps(entry, materialIds);
                    seedConsumption(jdbc, table.rows(), materialIds);
                }
                case "08_purchase_orders.csv" -> {
                    requireMaps(entry, supplierIds);
                    seedPurchaseOrders(jdbc, table.rows(), supplierIds);
                    purchaseOrderIds.putAll(readIdMap(jdbc,
                            "SELECT erp_purchase_order_id, purchase_order_id FROM purchase_orders"));
                }
                case "09_purchase_order_items.csv" -> {
                    requireMaps(entry, purchaseOrderIds, materialIds, contractIds, warehouseIds);
                    seedPurchaseOrderItems(
                            jdbc, table.rows(), purchaseOrderIds, materialIds, contractIds, warehouseIds);
                    purchaseOrderItemIds.putAll(readIdMap(jdbc,
                            "SELECT erp_purchase_order_item_id, purchase_order_item_id FROM purchase_order_items"));
                }
                case "10_goods_receipts.csv" -> {
                    requireMaps(entry, purchaseOrderItemIds, warehouseIds);
                    seedGoodsReceipts(jdbc, table.rows(), purchaseOrderItemIds, warehouseIds);
                }
                default -> throw new IllegalStateException(
                        "Unsupported ERP seed file in manifest: " + entry.fileName());
            }
        }
    }

    private static void seedMaterials(JdbcTemplate jdbc, List<Map<String, String>> rows) {
        String sql = """
                INSERT INTO materials (
                    erp_material_id, material_code, material_name, material_category,
                    base_unit, criticality, active, erp_group_code, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_material_id) DO UPDATE SET
                    material_code = EXCLUDED.material_code,
                    material_name = EXCLUDED.material_name,
                    material_category = EXCLUDED.material_category,
                    base_unit = EXCLUDED.base_unit,
                    criticality = EXCLUDED.criticality,
                    active = EXCLUDED.active,
                    erp_group_code = EXCLUDED.erp_group_code,
                    updated_at = EXCLUDED.updated_at
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "material_id"), required(row, "material_code"),
                required(row, "material_name"), required(row, "material_category"),
                required(row, "base_unit"), required(row, "criticality"),
                bool(row, "active"), required(row, "erp_group_code"),
                timestamp(row, "created_at"), timestamp(row, "updated_at")));
    }

    private static void seedSuppliers(JdbcTemplate jdbc, List<Map<String, String>> rows) {
        String sql = """
                INSERT INTO suppliers (
                    erp_supplier_id, supplier_code, supplier_name, country_code,
                    supplier_status, risk_level, feoc_status, certifications,
                    iatf_16949_certified, ppap_approved, active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_supplier_id) DO UPDATE SET
                    supplier_code = EXCLUDED.supplier_code,
                    supplier_name = EXCLUDED.supplier_name,
                    country_code = EXCLUDED.country_code,
                    supplier_status = EXCLUDED.supplier_status,
                    risk_level = EXCLUDED.risk_level,
                    feoc_status = EXCLUDED.feoc_status,
                    certifications = EXCLUDED.certifications,
                    iatf_16949_certified = EXCLUDED.iatf_16949_certified,
                    ppap_approved = EXCLUDED.ppap_approved,
                    active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at
                """;
        rows.forEach(row -> {
            String certifications = required(row, "certifications");
            boolean active = !"INACTIVE".equals(required(row, "supplier_status"));
            jdbc.update(sql,
                    required(row, "supplier_id"), required(row, "supplier_code"),
                    required(row, "supplier_name"), required(row, "country_code"),
                    required(row, "supplier_status"), required(row, "risk_level"),
                    bool(row, "feoc_status") ? "YES" : "NO", certifications,
                    certifications.contains("IATF16949"), certifications.contains("PPAP"), active,
                    timestamp(row, "created_at"), timestamp(row, "updated_at"));
        });
    }

    private static void seedWarehouses(JdbcTemplate jdbc, List<Map<String, String>> rows) {
        String sql = """
                INSERT INTO warehouses (
                    erp_warehouse_id, warehouse_code, warehouse_name, country_code,
                    timezone, warehouse_type, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_warehouse_id) DO UPDATE SET
                    warehouse_code = EXCLUDED.warehouse_code,
                    warehouse_name = EXCLUDED.warehouse_name,
                    country_code = EXCLUDED.country_code,
                    timezone = EXCLUDED.timezone,
                    warehouse_type = EXCLUDED.warehouse_type,
                    active = EXCLUDED.active
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "warehouse_id"), required(row, "warehouse_code"),
                required(row, "warehouse_name"), required(row, "country_code"),
                required(row, "timezone"), required(row, "warehouse_type"), bool(row, "active")));
    }

    private static void seedContracts(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> materialIds,
            Map<String, Long> supplierIds) {
        String sql = """
                INSERT INTO contracts (
                    erp_contract_id, contract_number, supplier_id, material_id, contract_name,
                    status, start_date, end_date, document_source, document_path,
                    source_document_id, contract_role, supplier_approval_status,
                    source_indexed_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_contract_id) DO UPDATE SET
                    contract_number = EXCLUDED.contract_number,
                    supplier_id = EXCLUDED.supplier_id,
                    material_id = EXCLUDED.material_id,
                    contract_name = EXCLUDED.contract_name,
                    status = EXCLUDED.status,
                    start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date,
                    document_source = EXCLUDED.document_source,
                    document_path = EXCLUDED.document_path,
                    source_document_id = EXCLUDED.source_document_id,
                    contract_role = EXCLUDED.contract_role,
                    supplier_approval_status = EXCLUDED.supplier_approval_status,
                    source_indexed_at = EXCLUDED.source_indexed_at,
                    updated_at = EXCLUDED.updated_at
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "contract_id"), required(row, "contract_number"),
                reference(supplierIds, row, "supplier_id"), reference(materialIds, row, "material_id"),
                required(row, "contract_name"), required(row, "contract_status"),
                date(row, "effective_date"), date(row, "expiration_date"),
                required(row, "document_source"), required(row, "document_path"),
                required(row, "document_id"), required(row, "contract_role"),
                required(row, "supplier_approval_status"), timestamp(row, "indexed_at"),
                timestamp(row, "updated_at")));
    }

    private static void seedSupplierMaterials(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> materialIds,
            Map<String, Long> supplierIds,
            Map<String, Long> contractIds) {
        String sql = """
                INSERT INTO supplier_materials (
                    erp_supplier_material_id, supplier_id, material_id, contract_id,
                    supply_share_ratio, lead_time_days, minimum_order_quantity,
                    approved_status, priority_rank, is_alternative, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_supplier_material_id) DO UPDATE SET
                    supplier_id = EXCLUDED.supplier_id,
                    material_id = EXCLUDED.material_id,
                    contract_id = EXCLUDED.contract_id,
                    supply_share_ratio = EXCLUDED.supply_share_ratio,
                    lead_time_days = EXCLUDED.lead_time_days,
                    minimum_order_quantity = EXCLUDED.minimum_order_quantity,
                    approved_status = EXCLUDED.approved_status,
                    priority_rank = EXCLUDED.priority_rank,
                    is_alternative = EXCLUDED.is_alternative,
                    valid_from = EXCLUDED.valid_from,
                    valid_to = EXCLUDED.valid_to
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "supplier_material_id"), reference(supplierIds, row, "supplier_id"),
                reference(materialIds, row, "material_id"), reference(contractIds, row, "contract_id"),
                decimal(row, "supply_share_ratio"), integer(row, "lead_time_days"),
                decimal(row, "minimum_order_quantity"), required(row, "approved_status"),
                integer(row, "priority_rank"), bool(row, "is_alternative"),
                date(row, "valid_from"), nullableDate(row, "valid_to")));
    }

    private static void seedInventory(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> materialIds,
            Map<String, Long> warehouseIds) {
        String sql = """
                INSERT INTO inventory_snapshots (
                    erp_inventory_snapshot_id, material_id, warehouse_id, on_hand_quantity,
                    reserved_quantity, blocked_quantity, quality_hold_quantity, safety_stock_quantity,
                    snapshot_at, is_current, source_unit, normalized_unit, data_quality_flag
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_inventory_snapshot_id) DO UPDATE SET
                    material_id = EXCLUDED.material_id,
                    warehouse_id = EXCLUDED.warehouse_id,
                    on_hand_quantity = EXCLUDED.on_hand_quantity,
                    reserved_quantity = EXCLUDED.reserved_quantity,
                    blocked_quantity = EXCLUDED.blocked_quantity,
                    quality_hold_quantity = EXCLUDED.quality_hold_quantity,
                    safety_stock_quantity = EXCLUDED.safety_stock_quantity,
                    snapshot_at = EXCLUDED.snapshot_at,
                    is_current = EXCLUDED.is_current,
                    source_unit = EXCLUDED.source_unit,
                    normalized_unit = EXCLUDED.normalized_unit,
                    data_quality_flag = EXCLUDED.data_quality_flag
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "inventory_snapshot_id"), reference(materialIds, row, "material_id"),
                reference(warehouseIds, row, "warehouse_id"), decimal(row, "on_hand_quantity"),
                decimal(row, "reserved_quantity"), decimal(row, "blocked_quantity"),
                decimal(row, "quality_hold_quantity"), decimal(row, "safety_stock_quantity"),
                timestamp(row, "snapshot_at"), bool(row, "is_current"),
                required(row, "source_unit"), required(row, "normalized_unit"),
                required(row, "data_quality_flag")));
    }

    private static void seedConsumption(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> materialIds) {
        String sql = """
                INSERT INTO material_consumptions (
                    erp_consumption_id, material_id, plant_code, average_daily_usage,
                    calculation_window_days, calculated_at, is_current, data_quality_flag
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_consumption_id) DO UPDATE SET
                    material_id = EXCLUDED.material_id,
                    plant_code = EXCLUDED.plant_code,
                    average_daily_usage = EXCLUDED.average_daily_usage,
                    calculation_window_days = EXCLUDED.calculation_window_days,
                    calculated_at = EXCLUDED.calculated_at,
                    is_current = EXCLUDED.is_current,
                    data_quality_flag = EXCLUDED.data_quality_flag
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "consumption_id"), reference(materialIds, row, "material_id"),
                required(row, "plant_code"), nullableDecimal(row, "average_daily_usage"),
                integer(row, "calculation_window_days"), timestamp(row, "calculated_at"),
                bool(row, "is_current"), required(row, "data_quality_flag")));
    }

    private static void seedPurchaseOrders(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> supplierIds) {
        String sql = """
                INSERT INTO purchase_orders (
                    erp_purchase_order_id, po_number, supplier_id, order_date, currency,
                    order_status, transport_mode, port_of_entry, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_purchase_order_id) DO UPDATE SET
                    po_number = EXCLUDED.po_number,
                    supplier_id = EXCLUDED.supplier_id,
                    order_date = EXCLUDED.order_date,
                    currency = EXCLUDED.currency,
                    order_status = EXCLUDED.order_status,
                    transport_mode = EXCLUDED.transport_mode,
                    port_of_entry = EXCLUDED.port_of_entry,
                    updated_at = EXCLUDED.updated_at
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "purchase_order_id"), required(row, "po_number"),
                reference(supplierIds, row, "supplier_id"), date(row, "order_date"),
                required(row, "currency"), required(row, "order_status"),
                required(row, "transport_mode"), nullable(row, "port_of_entry"),
                timestamp(row, "created_at"), timestamp(row, "updated_at")));
    }

    private static void seedPurchaseOrderItems(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> purchaseOrderIds,
            Map<String, Long> materialIds,
            Map<String, Long> contractIds,
            Map<String, Long> warehouseIds) {
        String sql = """
                INSERT INTO purchase_order_items (
                    erp_purchase_order_item_id, purchase_order_id, material_id, contract_id,
                    ordered_quantity, received_quantity, unit, expected_arrival_date,
                    confirmed_arrival_date, unit_price, incoterm, destination_warehouse_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_purchase_order_item_id) DO UPDATE SET
                    purchase_order_id = EXCLUDED.purchase_order_id,
                    material_id = EXCLUDED.material_id,
                    contract_id = EXCLUDED.contract_id,
                    ordered_quantity = EXCLUDED.ordered_quantity,
                    received_quantity = EXCLUDED.received_quantity,
                    unit = EXCLUDED.unit,
                    expected_arrival_date = EXCLUDED.expected_arrival_date,
                    confirmed_arrival_date = EXCLUDED.confirmed_arrival_date,
                    unit_price = EXCLUDED.unit_price,
                    incoterm = EXCLUDED.incoterm,
                    destination_warehouse_id = EXCLUDED.destination_warehouse_id
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "purchase_order_item_id"),
                reference(purchaseOrderIds, row, "purchase_order_id"),
                reference(materialIds, row, "material_id"), reference(contractIds, row, "contract_id"),
                decimal(row, "ordered_quantity"), decimal(row, "received_quantity"),
                required(row, "unit"), nullableDate(row, "expected_arrival_date"),
                nullableDate(row, "confirmed_arrival_date"), decimal(row, "unit_price"),
                required(row, "incoterm"), reference(warehouseIds, row, "destination_warehouse_id")));
    }

    private static void seedGoodsReceipts(
            JdbcTemplate jdbc,
            List<Map<String, String>> rows,
            Map<String, Long> purchaseOrderItemIds,
            Map<String, Long> warehouseIds) {
        String sql = """
                INSERT INTO goods_receipts (
                    erp_goods_receipt_id, purchase_order_item_id, received_quantity,
                    received_at, warehouse_id, quality_status, batch_number, quality_released_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (erp_goods_receipt_id) DO UPDATE SET
                    purchase_order_item_id = EXCLUDED.purchase_order_item_id,
                    received_quantity = EXCLUDED.received_quantity,
                    received_at = EXCLUDED.received_at,
                    warehouse_id = EXCLUDED.warehouse_id,
                    quality_status = EXCLUDED.quality_status,
                    batch_number = EXCLUDED.batch_number,
                    quality_released_at = EXCLUDED.quality_released_at
                """;
        rows.forEach(row -> jdbc.update(sql,
                required(row, "goods_receipt_id"),
                reference(purchaseOrderItemIds, row, "purchase_order_item_id"),
                decimal(row, "received_quantity"), timestamp(row, "received_at"),
                reference(warehouseIds, row, "warehouse_id"), required(row, "quality_status"),
                required(row, "batch_number"), nullableTimestamp(row, "quality_released_at")));
    }

    private static List<ManifestEntry> readManifest(Path root) {
        CsvTable table = readCsv(resolveInside(root, "00_manifest.csv"));
        List<ManifestEntry> entries = table.rows().stream()
                .map(row -> new ManifestEntry(
                        integer(row, "load_order"), required(row, "file_name"),
                        required(row, "target_table"), integer(row, "row_count")))
                .sorted((left, right) -> Integer.compare(left.loadOrder(), right.loadOrder()))
                .toList();
        if (entries.size() != 10) {
            throw new IllegalStateException("ERP manifest must contain exactly 10 data files");
        }
        return entries;
    }

    private static CsvTable readCsv(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required ERP seed file is missing: " + path);
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IllegalStateException("ERP CSV has no header: " + path);
            }
            List<String> headers = parseCsvLine(stripBom(headerLine));
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseCsvLine(line);
                if (values.size() != headers.size()) {
                    throw new IllegalStateException(
                            path.getFileName() + " line " + lineNumber + " has " + values.size()
                                    + " columns; expected " + headers.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    row.put(headers.get(index), values.get(index));
                }
                rows.add(row);
            }
            return new CsvTable(List.copyOf(headers), List.copyOf(rows));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read ERP CSV: " + path, exception);
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unclosed quote in ERP CSV line");
        }
        fields.add(field.toString().trim());
        return fields;
    }

    private static Path resolveInside(Path root, String fileName) {
        Path resolved = root.resolve(fileName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("ERP manifest path escapes seed directory: " + fileName);
        }
        return resolved;
    }

    private static Map<String, Long> readIdMap(JdbcTemplate jdbc, String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, (rs, rowNumber) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    @SafeVarargs
    private static void requireMaps(ManifestEntry entry, Map<String, Long>... maps) {
        for (Map<String, Long> map : maps) {
            if (map.isEmpty()) {
                throw new IllegalStateException(
                        "ERP manifest dependency was not loaded before " + entry.fileName());
            }
        }
    }

    private static Long reference(Map<String, Long> ids, Map<String, String> row, String column) {
        String externalId = required(row, column);
        Long internalId = ids.get(externalId);
        if (internalId == null) {
            throw new IllegalStateException(
                    "Unknown ERP foreign key " + column + "=" + externalId);
        }
        return internalId;
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required ERP CSV value is blank: " + column);
        }
        return value.trim();
    }

    private static String nullable(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean bool(Map<String, String> row, String column) {
        String value = required(row, column);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException("Invalid boolean " + column + "=" + value);
        }
        return Boolean.parseBoolean(value);
    }

    private static int integer(Map<String, String> row, String column) {
        return Integer.parseInt(required(row, column));
    }

    private static BigDecimal decimal(Map<String, String> row, String column) {
        return new BigDecimal(required(row, column));
    }

    private static BigDecimal nullableDecimal(Map<String, String> row, String column) {
        String value = nullable(row, column);
        return value == null ? null : new BigDecimal(value);
    }

    private static LocalDate date(Map<String, String> row, String column) {
        return LocalDate.parse(required(row, column));
    }

    private static LocalDate nullableDate(Map<String, String> row, String column) {
        String value = nullable(row, column);
        return value == null ? null : LocalDate.parse(value);
    }

    private static OffsetDateTime timestamp(Map<String, String> row, String column) {
        return OffsetDateTime.parse(required(row, column));
    }

    private static OffsetDateTime nullableTimestamp(Map<String, String> row, String column) {
        String value = nullable(row, column);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record ManifestEntry(int loadOrder, String fileName, String targetTable, int rowCount) {}

    private record CsvTable(List<String> headers, List<Map<String, String>> rows) {}
}

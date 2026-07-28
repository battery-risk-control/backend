package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.CollectionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * F4: BDI/GDACS 과거 이력 로컬 아카이브(팀 공용 수집 데이터)를 raw_events에 일괄 적재합니다.
 * 경로는 app.historical-data.extra-path 설정을 따릅니다.
 */
@Service
public class HistoricalDataImportService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalDataImportService.class);
    private static final int BATCH_SIZE = 2_000;
    private static final String INSERT_SQL = """
            INSERT INTO raw_events
                (source, data_type, external_id, content_hash, title, content, source_url, country_code, payload_json, collected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, external_id) WHERE external_id IS NOT NULL DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.historical-data.extra-path:}")
    private String extraPath;

    public HistoricalDataImportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CollectionDto.CollectionSummary importAll() {
        List<CollectionDto.CollectionRunResult> results = new ArrayList<>();
        if (extraPath == null || extraPath.isBlank()) {
            log.warn("app.historical-data.extra-path 미설정 - BDI/GDACS 과거 아카이브 적재를 건너뜁니다.");
        } else {
            results.add(importBdiIndex());
            results.add(importGdacsHistorical());
        }
        return new CollectionDto.CollectionSummary(results);
    }

    /** 발틱 운임지수(BDI) 일별 시세를 raw_events에 적재합니다. 글로벌 지표라 country_code는 null입니다. */
    private CollectionDto.CollectionRunResult importBdiIndex() {
        String source = "BDI_HISTORICAL";
        String dataType = "FREIGHT_INDEX";
        Path file = Path.of(extraPath, "BDI 2020.01.01-2026.07.14.csv");
        if (!Files.isRegularFile(file)) {
            log.warn("{} 파일을 찾을 수 없어 건너뜁니다: {}", source, file);
            return new CollectionDto.CollectionRunResult(source, dataType, 0, 0, 0, "SKIPPED_FILE_NOT_FOUND", file.toString());
        }

        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        int totalFetched = 0;
        int totalInserted = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return new CollectionDto.CollectionRunResult(source, dataType, 0, 0, 0, "SUCCESS", null);
            }
            List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = stripQuotedCsvLine(line);
                if (cols.length < 2) continue;
                LocalDate date;
                double price;
                try {
                    date = LocalDate.parse(cols[0].trim(), dateFormat);
                    price = Double.parseDouble(cols[1].trim().replace(",", ""));
                } catch (RuntimeException exception) {
                    continue;
                }
                String externalId = "BDI_" + date;
                String content = "bdi_price=" + price;
                String contentHash = sha256(externalId + "|" + content);
                batchArgs.add(new Object[]{
                        source, dataType, externalId, contentHash,
                        "Baltic Dry Index " + date, content, null, null,
                        "{\"date\":\"" + date + "\",\"price\":" + price + "}",
                        Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant())
                });
                totalFetched++;
                if (batchArgs.size() == BATCH_SIZE) {
                    totalInserted += executeBatchInsert(batchArgs);
                    batchArgs.clear();
                }
            }
            if (!batchArgs.isEmpty()) {
                totalInserted += executeBatchInsert(batchArgs);
            }
            log.info("{} 파일 적재 완료: records={}", source, totalFetched);
        } catch (IOException exception) {
            log.warn("{} 파일 읽기 실패: {}", source, exception.getMessage());
            return new CollectionDto.CollectionRunResult(source, dataType, 0, 0, 0, "FAILED", exception.getMessage());
        }
        return new CollectionDto.CollectionRunResult(source, dataType, totalFetched, totalInserted, 0, "SUCCESS", null);
    }

    /** 광산·항구 500km 이내로 사전 필터링된 GDACS 과거 재난 이력을 raw_events에 적재합니다. */
    private CollectionDto.CollectionRunResult importGdacsHistorical() {
        String source = "GDACS_HISTORICAL";
        String dataType = "DISASTER_HISTORY";
        Path file = Path.of(extraPath, "gdacs_historical_filtered_500km.json");
        if (!Files.isRegularFile(file)) {
            log.warn("{} 파일을 찾을 수 없어 건너뜁니다: {}", source, file);
            return new CollectionDto.CollectionRunResult(source, dataType, 0, 0, 0, "SKIPPED_FILE_NOT_FOUND", file.toString());
        }

        int totalFetched = 0;
        int totalInserted = 0;
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            List<Object[]> batchArgs = new ArrayList<>();
            for (JsonNode feature : root.path("features")) {
                JsonNode properties = feature.path("properties");
                String eventId = properties.path("eventid").asText(null);
                if (eventId == null) continue;
                String externalId = "GDACS-" + eventId;
                String name = properties.path("name").asText("");
                String alertLevel = properties.path("alertlevel").asText("Green");
                double magnitude = properties.path("severitydata").path("severity").asDouble(0.0);
                String mappedTarget = properties.path("mapped_target_id").asText("");
                double distanceKm = properties.path("distance_km").asDouble(0.0);
                String countryCode = extractCountryCode(mappedTarget);
                String content = "alertlevel=" + alertLevel + ",magnitude=" + magnitude
                        + ",mapped_target=" + mappedTarget + ",distance_km=" + distanceKm;
                String contentHash = sha256(externalId + "|" + content);
                Instant collectedAt = parseGdacsDate(properties.path("fromdate").asText(""));
                batchArgs.add(new Object[]{
                        source, dataType, externalId, contentHash, name, content, null, countryCode,
                        properties.toString(), Timestamp.from(collectedAt)
                });
                totalFetched++;
            }
            if (!batchArgs.isEmpty()) {
                totalInserted += executeBatchInsert(batchArgs);
            }
            log.info("{} 파일 적재 완료: records={}", source, totalFetched);
        } catch (IOException exception) {
            log.warn("{} 파일 읽기 실패: {}", source, exception.getMessage());
            return new CollectionDto.CollectionRunResult(source, dataType, 0, 0, 0, "FAILED", exception.getMessage());
        }
        return new CollectionDto.CollectionRunResult(source, dataType, totalFetched, totalInserted, 0, "SUCCESS", null);
    }

    private String[] stripQuotedCsvLine(String line) {
        String stripped = line.trim();
        if (stripped.startsWith("﻿")) stripped = stripped.substring(1);
        if (stripped.startsWith("\"")) stripped = stripped.substring(1);
        if (stripped.endsWith("\"")) stripped = stripped.substring(0, stripped.length() - 1);
        return stripped.split("\",\"", -1);
    }

    private Instant parseGdacsDate(String isoLocalDateTime) {
        if (isoLocalDateTime.isBlank()) return Instant.now();
        return LocalDateTime.parse(isoLocalDateTime).toInstant(ZoneOffset.UTC);
    }

    private int executeBatchInsert(List<Object[]> batchArgs) {
        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
        int inserted = 0;
        for (int result : results) {
            if (result > 0) inserted += result;
        }
        return inserted;
    }

    private String extractCountryCode(String siteCode) {
        return switch (siteCode) {
            case "AR_SOMBRE_MUERTO" -> "AR";
            case "CD_KAMBOVE", "CD_MUTANDA" -> "CD";
            case "CL_ATACAMA", "PORT_ANTOFAGASTA" -> "CL";
            case "ID_MOROWALI", "ID_WEDA_BAY", "PORT_MOROWALI", "PORT_WEDA_BAY" -> "ID";
            default -> null;
        };
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

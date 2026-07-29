package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 뉴스 이벤트를 외부 데이터(기상·GDACS·yfinance·GDELT Event 아카이브)와 날짜/국가/자재 키로 조인하는 공용 로직입니다.
 * 실시간 분석 트리거(CollectionService)가 사용합니다.
 */
@Service
public class HistoricalFeatureJoinService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalFeatureJoinService.class);
    private static final int GDACS_MATCH_WINDOW_DAYS = 7;
    private static final int YFINANCE_MATCH_WINDOW_DAYS = 5;

    /** extraction_service.py의 mock 추출기가 지원하는 자재만 매핑 가능(NICKEL/COBALT/LITHIUM). */
    private static final Map<String, String> MATERIAL_CODE_TO_KOREAN_LABEL = Map.of(
            "NICKEL", "니켈", "COBALT", "코발트", "LITHIUM", "리튬"
    );

    private final RawEventRepository rawEventRepository;
    private final GdeltEventArchiveService gdeltEventArchiveService;
    private List<String[]> yfinanceRowsCache;

    public HistoricalFeatureJoinService(
            RawEventRepository rawEventRepository, GdeltEventArchiveService gdeltEventArchiveService) {
        this.rawEventRepository = rawEventRepository;
        this.gdeltEventArchiveService = gdeltEventArchiveService;
    }

    public record JoinResult(
            Integer gdacsAlertLevel, Double stockVolatility20d, Double stockReturn1d,
            Double goldsteinScale, String gdeltMatchType
    ) {}

    public JoinResult join(String countryCode, Instant eventTimestamp, String material, String sourceUrl) {
        Integer gdacsLevel = lookupGdacsLevel(countryCode, eventTimestamp);
        YfinanceMatch yfinanceMatch = material != null ? lookupYfinance(material, eventTimestamp.atZone(ZoneOffset.UTC).toLocalDate()) : null;
        var gdeltMatch = gdeltEventArchiveService.lookup(eventTimestamp, countryCode, sourceUrl);

        return new JoinResult(
                gdacsLevel,
                yfinanceMatch != null ? yfinanceMatch.volatility20d() : null,
                yfinanceMatch != null ? yfinanceMatch.return1d() : null,
                gdeltMatch.map(GdeltEventArchiveService.EventMatch::goldsteinScale).orElse(null),
                gdeltMatch.map(GdeltEventArchiveService.EventMatch::matchType).orElse("NO_MATCH")
        );
    }

    /** 같은 국가·같은 날짜에 이 뉴스가 몇 건 있었는지(관심도 proxy). */
    public long countNewsOnSameDay(String countryCode, Instant eventTimestamp) {
        if (countryCode == null) return 0;
        LocalDate eventDate = eventTimestamp.atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = eventDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = eventDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return rawEventRepository.findByDataTypeAndCountryCodeAndCollectedAtBetween("NEWS", countryCode, start, end).size();
    }

    /** 과거 아카이브(DISASTER_HISTORY)에서 날짜 근처(±7일) 이력이 있으면 그 값을, 없으면 실시간 최신 GDACS로 대체합니다. */
    private Integer lookupGdacsLevel(String countryCode, Instant eventTimestamp) {
        if (countryCode == null) return null;
        Instant start = eventTimestamp.minus(GDACS_MATCH_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant end = eventTimestamp.plus(GDACS_MATCH_WINDOW_DAYS, ChronoUnit.DAYS);
        List<RawEvent> alerts = rawEventRepository.findByDataTypeAndCountryCodeAndCollectedAtBetween(
                "DISASTER_HISTORY", countryCode, start, end);

        Integer historicalMax = null;
        for (RawEvent alert : alerts) {
            Integer level = parseAlertLevel(alert.getContent());
            if (level == null) continue;
            historicalMax = historicalMax == null ? level : Math.max(historicalMax, level);
        }
        if (historicalMax != null) return historicalMax;

        return rawEventRepository.findFirstByDataTypeAndCountryCodeOrderByCollectedAtDesc("DISASTER", countryCode)
                .map(event -> parseAlertLevel(event.getContent()))
                .orElse(0);
    }

    private record YfinanceMatch(Double volatility20d, Double return1d) {}

    private YfinanceMatch lookupYfinance(String materialCode, LocalDate eventDate) {
        String label = MATERIAL_CODE_TO_KOREAN_LABEL.get(materialCode);
        if (label == null) return null;
        List<String[]> rows = loadYfinanceRows();
        if (rows.isEmpty()) return null;

        String[] header = rows.get(0);
        int materialIdx = -1, eventDateIdx = -1, volIdx = -1, returnIdx = -1;
        for (int i = 0; i < header.length; i++) {
            switch (header[i].trim()) {
                case "material_or_label" -> materialIdx = i;
                case "event_date" -> eventDateIdx = i;
                case "stock_vol_20d" -> volIdx = i;
                case "stock_return_1d" -> returnIdx = i;
                default -> { }
            }
        }
        if (materialIdx < 0 || eventDateIdx < 0) return null;

        String[] closestRow = null;
        long closestDiff = Long.MAX_VALUE;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length <= materialIdx || !label.equals(row[materialIdx].trim())) continue;
            LocalDate rowDate = parseIsoDate(eventDateIdx < row.length ? row[eventDateIdx].trim() : "");
            if (rowDate == null) continue;
            long diff = Math.abs(ChronoUnit.DAYS.between(eventDate, rowDate));
            if (diff < closestDiff) {
                closestDiff = diff;
                closestRow = row;
            }
        }
        if (closestRow == null || closestDiff > YFINANCE_MATCH_WINDOW_DAYS) return null;

        Double vol = volIdx >= 0 && volIdx < closestRow.length ? parseDoubleSafe(closestRow[volIdx]) : null;
        Double ret = returnIdx >= 0 && returnIdx < closestRow.length ? parseDoubleSafe(closestRow[returnIdx]) : null;
        return new YfinanceMatch(vol, ret);
    }

    private List<String[]> loadYfinanceRows() {
        if (yfinanceRowsCache != null) return yfinanceRowsCache;
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("seed-data/yfinance/all_materials_merged.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && line.startsWith("﻿")) line = line.substring(1);
                first = false;
                rows.add(line.split(",", -1));
            }
        } catch (IOException exception) {
            log.warn("yfinance CSV 로드 실패: {}", exception.getMessage());
        }
        yfinanceRowsCache = rows;
        return rows;
    }

    private Integer parseAlertLevel(String content) {
        String level = parseKeyValueString(content, "alertlevel");
        if (level == null) return null;
        return switch (level) {
            case "Red" -> 2;
            case "Orange" -> 1;
            default -> 0;
        };
    }

    private LocalDate parseIsoDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Double parseDoubleSafe(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String parseKeyValueString(String content, String key) {
        if (content == null) return null;
        for (String part : content.split(",")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].trim().equals(key)) {
                return keyValue[1].trim();
            }
        }
        return null;
    }
}

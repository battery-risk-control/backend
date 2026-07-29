package com.example.batteryrisk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

/**
 * GDELT 2.0 이벤트 아카이브(15분 단위 export.CSV.zip)를 조회해 goldstein_scale을 얻습니다.
 * DOC 2.0 API(뉴스 검색)에는 이 필드가 없어서, 별도의 Event Export API를 씁니다.
 * 문서화된 61개 컬럼 포맷: https://www.gdeltproject.org/data/lookups/CSV.header.fieldids.xlsx
 */
@Service
public class GdeltEventArchiveService {
    private static final Logger log = LoggerFactory.getLogger(GdeltEventArchiveService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final int WINDOW_COUNT = 4;
    private static final int GOLDSTEIN_SCALE_INDEX = 30;
    private static final int ACTION_GEO_COUNTRY_INDEX = 53;
    private static final int SOURCE_URL_INDEX = 60;
    private static final int MIN_COLUMNS = 61;

    /** GDELT는 국가를 ISO 3166이 아니라 FIPS 10-4 코드로 표기합니다. */
    private static final Map<String, String> ISO2_TO_FIPS = Map.of(
            "CL", "CI", "ID", "ID", "AU", "AS", "CD", "CG", "AR", "AR", "CN", "CH", "PH", "RP"
    );
    private static final Map<String, String> FIPS_TO_ISO2 = ISO2_TO_FIPS.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private final RestClient restClient;
    private final Map<String, List<String[]>> fileCache = new ConcurrentHashMap<>();

    public GdeltEventArchiveService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(8_000);
        requestFactory.setReadTimeout(15_000);
        this.restClient = RestClient.builder()
                .baseUrl("http://data.gdeltproject.org/gdeltv2")
                .requestFactory(requestFactory)
                .build();
    }

    public record EventMatch(Double goldsteinScale, String matchType) {}

    /**
     * 기사 URL이 이벤트 아카이브에서 그대로 발견되면 그 행을 그대로 쓰고(가장 정확),
     * 못 찾으면 같은 시간대·같은 국가에서 발생한 이벤트들의 평균으로 근사합니다.
     */
    public Optional<EventMatch> lookup(Instant eventTimestamp, String countryCodeIso2, String sourceUrl) {
        List<String> windows = alignedWindows(eventTimestamp, WINDOW_COUNT);

        for (String window : windows) {
            for (String[] row : fetchFile(window)) {
                if (row.length >= MIN_COLUMNS && sourceUrl != null && sourceUrl.equals(row[SOURCE_URL_INDEX])) {
                    return Optional.of(new EventMatch(parseDouble(row[GOLDSTEIN_SCALE_INDEX]), "URL_EXACT"));
                }
            }
        }

        String fips = countryCodeIso2 == null ? null : ISO2_TO_FIPS.get(countryCodeIso2);
        if (fips == null) return Optional.empty();

        List<Double> goldsteinScores = new ArrayList<>();
        for (String window : windows) {
            for (String[] row : fetchFile(window)) {
                if (row.length < MIN_COLUMNS || !fips.equals(row[ACTION_GEO_COUNTRY_INDEX])) continue;
                Double goldstein = parseDouble(row[GOLDSTEIN_SCALE_INDEX]);
                if (goldstein != null) goldsteinScores.add(goldstein);
            }
        }
        if (goldsteinScores.isEmpty()) return Optional.empty();

        double avgGoldstein = goldsteinScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return Optional.of(new EventMatch(avgGoldstein, "COUNTRY_AGGREGATE"));
    }

    /** GDELT의 FIPS 10-4 국가 코드를 우리 시스템이 쓰는 ISO 3166 alpha-2로 변환합니다. 매핑에 없으면 null. */
    public String fipsToIso2(String fips) {
        return fips == null ? null : FIPS_TO_ISO2.get(fips);
    }

    private List<String[]> fetchFile(String windowTimestamp) {
        return fileCache.computeIfAbsent(windowTimestamp, this::downloadAndParse);
    }

    private List<String[]> downloadAndParse(String windowTimestamp) {
        String filename = windowTimestamp + ".export.CSV.zip";
        try {
            byte[] zipBytes = restClient.get().uri("/" + filename).retrieve().body(byte[].class);
            if (zipBytes == null) return List.of();
            List<String[]> rows = new ArrayList<>();
            try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                if (zipInputStream.getNextEntry() == null) return List.of();
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipInputStream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    rows.add(line.split("\t", -1));
                }
            }
            return rows;
        } catch (IOException | RuntimeException exception) {
            log.debug("GDELT Event 아카이브 다운로드 실패 ({}): {}", filename, exception.getMessage());
            return List.of();
        }
    }

    private List<String> alignedWindows(Instant eventTimestamp, int count) {
        long alignedEpochSecond = (eventTimestamp.getEpochSecond() / 900) * 900;
        Instant aligned = Instant.ofEpochSecond(alignedEpochSecond);
        List<String> windows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            windows.add(FILE_TIMESTAMP.format(aligned.plusSeconds(900L * i)));
        }
        return windows;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

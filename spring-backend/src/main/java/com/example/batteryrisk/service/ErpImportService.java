package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpAdminDto;
import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import com.example.batteryrisk.repository.ErpRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 구매팀 "데이터 관리" 화면의 ERP CSV 일괄 적재.
 *
 * <p>계약서 업로드({@link ContractUploadService})와 같은 2단계 구조다 — {@link #preview}는 DB를
 * 건드리지 않고 분석 결과만 돌려주고, {@link #commit}에서 실제로 적재한다. 잡 ID도 스테이징
 * 테이블도 없다. 두 호출 모두 파일 자체를 받으므로 프론트가 File을 들고 있다가 다시 보낸다.
 *
 * <p>이 클래스는 <b>파싱과 검증만</b> 한다. 실제 DB 쓰기는 전부 {@link ErpAdminService}의 기존
 * upsert에 위임한다 — 같은 SQL을 두 벌 두면 한쪽만 고쳐지는 사고가 나기 때문이다.
 *
 * <p>CSV 파서를 {@link com.example.batteryrisk.config.ErpSeedConfig}와 공유하지 않는 이유:
 * 시드 쪽은 깨진 줄을 만나면 예외를 던지고 부팅을 세운다(그게 맞다 — 고정 시드 패키지니까).
 * 이쪽은 사용자가 올린 파일이라 <b>끝까지 읽어 문제를 전부 모아 보여줘야</b> 한다. 첫 줄에서
 * 멈추면 "고치고 다시 올리기"를 오류 개수만큼 반복하게 된다.
 */
@Service
public class ErpImportService {
    /** 파일당 응답에 실어 보내는 이슈 최대 개수. 개수 집계는 자르지 않고 목록만 자른다. */
    private static final int MAX_ISSUES_PER_FILE = 50;
    /** "내용 분석" 칸에 보여줄 미리보기 행 수. */
    private static final int SAMPLE_ROW_COUNT = 5;

    private final ErpAdminService erpAdminService;
    private final ErpRepository repository;
    private final long maxFileSize;

    public ErpImportService(
            ErpAdminService erpAdminService,
            ErpRepository repository,
            @Value("${app.upload.max-file-size:52428800}") long maxFileSize) {
        this.erpAdminService = erpAdminService;
        this.repository = repository;
        this.maxFileSize = maxFileSize;
    }

    // ---------------------------------------------------------------- 공개 API

    /** 1단계: 올린 CSV를 파싱·검증해 결과만 돌려준다. DB에는 아무것도 쓰지 않는다. */
    public ErpImportDto.PreviewResponse preview(List<MultipartFile> files) {
        List<ParsedFile> parsed = parseAll(files);
        List<ErpImportDto.FileAnalysis> analyses = analyzeAll(parsed);

        int totalRows = 0;
        int totalErrors = 0;
        int totalWarnings = 0;
        int totalDuplicates = 0;
        for (ErpImportDto.FileAnalysis analysis : analyses) {
            totalRows += analysis.rowCount();
            totalErrors += analysis.errorCount();
            totalWarnings += analysis.warningCount();
            totalDuplicates += analysis.duplicateCount();
        }

        // 대상 테이블별 적재 예정 건수 — 같은 테이블로 판별된 파일이 여럿이면 합산한다.
        Map<String, Integer> rowsByTable = new LinkedHashMap<>();
        for (ErpImportDto.FileAnalysis analysis : analyses) {
            if (analysis.targetTable() == null) continue;
            rowsByTable.merge(analysis.targetTable(), analysis.rowCount(), Integer::sum);
        }
        List<ErpImportDto.TableCount> summary = rowsByTable.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> TABLE_SPECS.get(entry.getKey()).loadOrder()))
                .map(entry -> new ErpImportDto.TableCount(
                        entry.getKey(), TABLE_SPECS.get(entry.getKey()).label(), entry.getValue()))
                .toList();

        return new ErpImportDto.PreviewResponse(
                analyses, totalRows, totalErrors, totalWarnings, totalDuplicates,
                qualityScore(totalRows, totalErrors, totalWarnings, totalDuplicates),
                totalErrors == 0 && totalRows > 0,
                summary);
    }

    /**
     * 2단계: 실제로 적재한다. 한 트랜잭션이라 한 행이라도 실패하면 전부 되돌아간다 — 자재만 들어가고
     * 계약은 빠진 절반 상태가 남으면 노출도 계산이 조용히 틀어지기 때문이다.
     *
     * <p>적재 전에 {@link #preview}와 같은 검증을 다시 돌린다. 프론트가 보낸 파일이 분석했던 그
     * 파일이라는 보장이 없어서다(사용자가 사이에 파일을 바꿔 끼울 수 있다).
     */
    @Transactional
    public ErpImportDto.CommitResponse commit(List<MultipartFile> files) {
        List<ParsedFile> parsed = parseAll(files);
        List<ErpImportDto.FileAnalysis> analyses = analyzeAll(parsed);

        int totalErrors = analyses.stream().mapToInt(ErpImportDto.FileAnalysis::errorCount).sum();
        if (totalErrors > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "오류 " + totalErrors + "건이 남아 있어 반영할 수 없습니다. 분석 결과를 확인하고 파일을 수정해 주세요.");
        }
        if (parsed.stream().allMatch(file -> file.rows().isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "반영할 데이터 행이 없습니다.");
        }

        // FK 의존 순서대로 적재한다. 계약보다 자재가 먼저 들어가야 계약의 material_id가 풀린다.
        List<ParsedFile> ordered = parsed.stream()
                .sorted(Comparator.comparingInt(file -> file.spec().loadOrder()))
                .toList();

        Map<String, int[]> countsByTable = new LinkedHashMap<>();   // [inserted, updated]
        for (ParsedFile file : ordered) {
            TableSpec spec = file.spec();
            int[] counts = countsByTable.computeIfAbsent(spec.table(), key -> new int[2]);
            for (DataRow row : file.rows()) {
                ErpAdminDto.UpsertResponse response;
                try {
                    response = spec.upsert().apply(erpAdminService, new RowView(row.values()));
                } catch (BusinessException exception) {
                    // 어느 파일 몇 번째 줄에서 터졌는지 붙여준다 — 이게 없으면 수백 행 중 어디가
                    // 문제인지 알 수 없어 사용자가 손쓸 방법이 없다.
                    throw new BusinessException(exception.getErrorCode(),
                            file.fileName() + " " + row.lineNumber() + "행: " + exception.getMessage());
                }
                if (response.created()) counts[0]++;
                else counts[1]++;
            }
        }

        List<ErpImportDto.TableResult> results = countsByTable.entrySet().stream()
                .map(entry -> new ErpImportDto.TableResult(
                        entry.getKey(), TABLE_SPECS.get(entry.getKey()).label(),
                        entry.getValue()[0], entry.getValue()[1]))
                .toList();
        return new ErpImportDto.CommitResponse(
                OffsetDateTime.now(),
                results.stream().mapToInt(ErpImportDto.TableResult::inserted).sum(),
                results.stream().mapToInt(ErpImportDto.TableResult::updated).sum(),
                results);
    }

    // ------------------------------------------------------------------ 검증

    private List<ParsedFile> parseAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "업로드된 파일이 없습니다.");
        }
        return files.stream().map(this::parse).toList();
    }

    /**
     * 파일 전체를 분석한다. FK 확인은 <b>DB에 이미 있거나 같은 배치에 함께 올라온 파일에 있으면</b>
     * 통과다 — 자재와 계약을 한꺼번에 올리는 게 정상 사용인데, DB만 보면 신규 자재를 참조하는
     * 계약이 전부 오류로 잡혀 아무것도 못 올리게 된다.
     */
    private List<ErpImportDto.FileAnalysis> analyzeAll(List<ParsedFile> parsed) {
        Map<String, Set<String>> batchKeys = new HashMap<>();
        for (ParsedFile file : parsed) {
            if (file.spec() == null || file.spec().businessKeyColumn() == null) continue;
            Set<String> keys = batchKeys.computeIfAbsent(file.spec().table(), key -> new HashSet<>());
            for (DataRow row : file.rows()) {
                String key = trimmed(row.values().get(file.spec().businessKeyColumn()));
                if (key != null) keys.add(key);
            }
        }
        Map<String, Map<String, Boolean>> fkCache = new HashMap<>();
        return parsed.stream().map(file -> analyze(file, batchKeys, fkCache)).toList();
    }

    private ErpImportDto.FileAnalysis analyze(
            ParsedFile file,
            Map<String, Set<String>> batchKeys,
            Map<String, Map<String, Boolean>> fkCache) {
        List<ErpImportDto.Issue> issues = new ArrayList<>(file.fileIssues());
        int errors = (int) file.fileIssues().stream().filter(i -> "ERROR".equals(i.level())).count();
        int warnings = (int) file.fileIssues().stream().filter(i -> "WARNING".equals(i.level())).count();
        int duplicates = 0;

        TableSpec spec = file.spec();
        if (spec == null) {
            return new ErpImportDto.FileAnalysis(
                    file.fileName(), null, null, 0, file.sizeBytes(), 0, file.headers().size(),
                    "ERROR", Math.max(errors, 1), warnings, 0,
                    List.of(), List.of(), limit(issues));
        }

        // --- 컬럼 매핑 ---
        List<ErpImportDto.ColumnMapping> columns = new ArrayList<>();
        Map<String, String> firstRow = file.rows().isEmpty() ? Map.of() : file.rows().get(0).values();
        for (ColumnSpec column : spec.columns()) {
            boolean present = file.headers().contains(column.csvColumn());
            String status = !present ? "MISSING" : column.targetField() == null ? "IGNORED" : "MAPPED";
            columns.add(new ErpImportDto.ColumnMapping(
                    column.csvColumn(), column.targetField(), column.description(),
                    column.required(), trimmed(firstRow.get(column.csvColumn())), status));
            if (!present && column.required()) {
                issues.add(new ErpImportDto.Issue("ERROR", null, column.csvColumn(),
                        "필수 컬럼이 파일에 없습니다."));
                errors++;
            }
            // 적재되지 않는 컬럼에 값이 들어 있으면 알려준다. 조용히 버리면 사용자는 그 값이
            // 반영된 줄 안다(예: supplier_materials의 available_capacity_quantity).
            if (present && column.targetField() == null && trimmed(firstRow.get(column.csvColumn())) != null) {
                issues.add(new ErpImportDto.Issue("WARNING", null, column.csvColumn(),
                        "이 컬럼은 적재되지 않고 무시됩니다."));
                warnings++;
            }
        }
        // 스펙에 없는 여분 컬럼도 무시된다는 걸 드러낸다.
        Set<String> known = spec.columns().stream().map(ColumnSpec::csvColumn).collect(Collectors.toSet());
        for (String header : file.headers()) {
            if (known.contains(header)) continue;
            columns.add(new ErpImportDto.ColumnMapping(
                    header, null, "시스템에 대응 필드가 없습니다", false,
                    trimmed(firstRow.get(header)), "IGNORED"));
            issues.add(new ErpImportDto.Issue("WARNING", null, header, "알 수 없는 컬럼이라 무시됩니다."));
            warnings++;
        }

        // --- 행 단위 검증 ---
        Set<String> seenKeys = new HashSet<>();
        for (DataRow row : file.rows()) {
            for (ColumnSpec column : spec.columns()) {
                if (!file.headers().contains(column.csvColumn())) continue;   // 위에서 이미 오류로 잡았다
                String raw = trimmed(row.values().get(column.csvColumn()));
                if (raw == null) {
                    if (column.required()) {
                        issues.add(new ErpImportDto.Issue("ERROR", row.lineNumber(), column.csvColumn(),
                                "필수 값이 비어 있습니다."));
                        errors++;
                    }
                    continue;
                }
                if (column.targetField() == null) continue;   // 무시되는 컬럼은 타입을 따지지 않는다
                if (!parses(column.type(), raw)) {
                    issues.add(new ErpImportDto.Issue("ERROR", row.lineNumber(), column.csvColumn(),
                            typeHint(column.type()) + " 형식이 아닙니다: \"" + raw + "\""));
                    errors++;
                    continue;
                }
                if (column.referencedTable() != null
                        && !existsReference(column.referencedTable(), raw, batchKeys, fkCache)) {
                    issues.add(new ErpImportDto.Issue("ERROR", row.lineNumber(), column.csvColumn(),
                            TABLE_SPECS.get(column.referencedTable()).label()
                                    + " \"" + raw + "\"을(를) 찾을 수 없습니다. 해당 파일을 함께 올려주세요."));
                    errors++;
                }
            }
            if (spec.businessKeyColumn() != null) {
                String key = trimmed(row.values().get(spec.businessKeyColumn()));
                if (key != null && !seenKeys.add(key)) {
                    issues.add(new ErpImportDto.Issue("DUPLICATE", row.lineNumber(),
                            spec.businessKeyColumn(), "같은 파일 안에 중복된 키입니다: " + key));
                    duplicates++;
                }
            }
        }

        String result = errors > 0 ? "ERROR" : (warnings > 0 || duplicates > 0) ? "WARNING" : "SUCCESS";
        return new ErpImportDto.FileAnalysis(
                file.fileName(), spec.table(), spec.label(), spec.loadOrder(), file.sizeBytes(),
                file.rows().size(), file.headers().size(), result, errors, warnings, duplicates,
                columns, sampleRows(file), limit(issues));
    }

    private static List<Map<String, String>> sampleRows(ParsedFile file) {
        return file.rows().stream().limit(SAMPLE_ROW_COUNT).map(DataRow::values).toList();
    }

    private static List<ErpImportDto.Issue> limit(List<ErpImportDto.Issue> issues) {
        if (issues.size() <= MAX_ISSUES_PER_FILE) return List.copyOf(issues);
        List<ErpImportDto.Issue> trimmed = new ArrayList<>(issues.subList(0, MAX_ISSUES_PER_FILE));
        trimmed.add(new ErpImportDto.Issue("WARNING", null, null,
                "이 밖에 " + (issues.size() - MAX_ISSUES_PER_FILE) + "건이 더 있습니다. 위 항목부터 수정해 주세요."));
        return List.copyOf(trimmed);
    }

    /** 0~100. 오류를 가장 무겁게, 중복·경고 순으로 깎는다. */
    private static int qualityScore(int rows, int errors, int warnings, int duplicates) {
        if (rows == 0) return 0;
        double penalty = (errors * 1.0 + duplicates * 0.5 + warnings * 0.3) / rows * 100;
        return (int) Math.max(0, Math.min(100, Math.round(100 - penalty)));
    }

    private boolean existsReference(
            String table, String key,
            Map<String, Set<String>> batchKeys,
            Map<String, Map<String, Boolean>> cache) {
        if (batchKeys.getOrDefault(table, Set.of()).contains(key)) return true;
        return cache.computeIfAbsent(table, t -> new HashMap<>())
                .computeIfAbsent(key, k -> switch (table) {
                    case "materials" -> repository.resolveMaterialId(k).isPresent();
                    case "suppliers" -> repository.resolveSupplierId(k).isPresent();
                    case "warehouses" -> repository.resolveWarehouseId(k).isPresent();
                    case "contracts" -> repository.resolveContractId(k).isPresent();
                    case "purchase_orders" -> repository.resolvePurchaseOrderId(k).isPresent();
                    case "purchase_order_items" -> repository.resolvePurchaseOrderItemId(k).isPresent();
                    default -> true;
                });
    }

    // ------------------------------------------------------------------ 파싱

    private ParsedFile parse(MultipartFile file) {
        String fileName = file.getOriginalFilename() == null ? "unnamed.csv" : file.getOriginalFilename();
        List<ErpImportDto.Issue> fileIssues = new ArrayList<>();

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", null, null,
                    "ERP 데이터는 CSV 파일만 올릴 수 있습니다."));
            return new ParsedFile(fileName, file.getSize(), null, List.of(), List.of(), fileIssues);
        }
        if (file.getSize() > maxFileSize) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", null, null,
                    "파일이 너무 큽니다(최대 " + (maxFileSize / 1024 / 1024) + "MB)."));
            return new ParsedFile(fileName, file.getSize(), null, List.of(), List.of(), fileIssues);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", null, null, "파일을 읽을 수 없습니다."));
            return new ParsedFile(fileName, file.getSize(), null, List.of(), List.of(), fileIssues);
        }

        List<String> lines = decode(content).lines().toList();
        int firstNonBlank = 0;
        while (firstNonBlank < lines.size() && lines.get(firstNonBlank).isBlank()) firstNonBlank++;
        if (firstNonBlank >= lines.size()) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", null, null, "빈 파일입니다."));
            return new ParsedFile(fileName, file.getSize(), null, List.of(), List.of(), fileIssues);
        }

        List<String> headers;
        try {
            headers = parseCsvLine(lines.get(firstNonBlank));
        } catch (IllegalArgumentException exception) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", firstNonBlank + 1, null,
                    "헤더를 읽을 수 없습니다: " + exception.getMessage()));
            return new ParsedFile(fileName, file.getSize(), null, List.of(), List.of(), fileIssues);
        }
        // 중복 헤더가 있으면 뒤 컬럼이 앞을 덮어써 값이 조용히 바뀐다. 먼저 막는다.
        if (new LinkedHashSet<>(headers).size() != headers.size()) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", firstNonBlank + 1, null,
                    "헤더에 같은 이름의 컬럼이 두 번 이상 있습니다."));
        }

        TableSpec spec = detect(fileName, headers);
        if (spec == null) {
            fileIssues.add(new ErpImportDto.Issue("ERROR", null, null,
                    "대상 테이블을 판별하지 못했습니다. 파일명을 materials.csv처럼 테이블 이름으로 맞추거나 "
                            + "표준 헤더를 사용해 주세요."));
        }

        List<DataRow> rows = new ArrayList<>();
        for (int index = firstNonBlank + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            int lineNumber = index + 1;
            List<String> values;
            try {
                values = parseCsvLine(line);
            } catch (IllegalArgumentException exception) {
                fileIssues.add(new ErpImportDto.Issue("ERROR", lineNumber, null, exception.getMessage()));
                continue;
            }
            if (values.size() != headers.size()) {
                fileIssues.add(new ErpImportDto.Issue("ERROR", lineNumber, null,
                        "컬럼 수가 " + values.size() + "개입니다. 헤더는 " + headers.size() + "개입니다."));
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(headers.get(column), values.get(column));
            }
            rows.add(new DataRow(lineNumber, row));
        }
        return new ParsedFile(fileName, file.getSize(), spec, List.copyOf(headers), List.copyOf(rows), fileIssues);
    }

    /**
     * UTF-8로 엄격하게 디코딩하고, UTF-8이 아니면 국내 엑셀의 기본 저장 인코딩인 MS949로 다시
     * 읽는다({@code ErpSeedConfig}와 같은 폴백). BOM은 떼어낸다 — 안 떼면 첫 헤더가
     * {@code "﻿material_id"}가 되어 컬럼을 못 찾는다.
     */
    private static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            text = new String(content, Charset.forName("MS949"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
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
        if (quoted) throw new IllegalArgumentException("따옴표가 닫히지 않았습니다.");
        fields.add(field.toString().trim());
        return fields;
    }

    /**
     * 대상 테이블 판별. 파일명이 우선이다 — 파일명이 맞으면 헤더가 어긋나도 그 테이블로 보고
     * "필수 컬럼 없음"을 짚어준다. 여기서 미판별로 떨어뜨리면 사용자는 무엇이 문제인지 못 본다.
     * 파일명으로 못 찾을 때만 헤더 서명으로 추정한다.
     */
    private static TableSpec detect(String fileName, List<String> headers) {
        String base = fileName.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceFirst("^\\d+[_-]", "");
        TableSpec byName = TABLE_SPECS.get(base);
        if (byName != null) return byName;

        Set<String> headerSet = new HashSet<>(headers);
        return TABLE_SPECS.values().stream()
                .filter(spec -> spec.columns().stream()
                        .filter(ColumnSpec::required)
                        .allMatch(column -> headerSet.contains(column.csvColumn())))
                .max(Comparator.comparingLong(spec -> spec.columns().stream()
                        .filter(column -> headerSet.contains(column.csvColumn())).count()))
                .orElse(null);
    }

    // ------------------------------------------------------------------ 타입

    private static boolean parses(FieldType type, String raw) {
        try {
            return switch (type) {
                case TEXT -> true;
                case INT -> { Integer.parseInt(raw); yield true; }
                case DECIMAL -> { new BigDecimal(raw); yield true; }
                case BOOLEAN -> BOOLEAN_TRUE.contains(raw.toLowerCase(Locale.ROOT))
                        || BOOLEAN_FALSE.contains(raw.toLowerCase(Locale.ROOT));
                case DATE -> { LocalDate.parse(raw); yield true; }
                case TIMESTAMP -> { OffsetDateTime.parse(raw); yield true; }
            };
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String typeHint(FieldType type) {
        return switch (type) {
            case TEXT -> "문자";
            case INT -> "정수";
            case DECIMAL -> "숫자";
            case BOOLEAN -> "true/false";
            case DATE -> "날짜(YYYY-MM-DD)";
            case TIMESTAMP -> "일시(2026-07-08T08:00:00+09:00)";
        };
    }

    private static final Set<String> BOOLEAN_TRUE = Set.of("true", "yes", "y", "1");
    private static final Set<String> BOOLEAN_FALSE = Set.of("false", "no", "n", "0");

    private static String trimmed(String value) {
        if (value == null) return null;
        String stripped = value.trim();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 한 행을 타입별로 꺼내 쓰는 뷰. 검증을 이미 통과한 뒤에만 쓴다. */
    private record RowView(Map<String, String> values) {
        String text(String column) { return trimmed(values.get(column)); }

        Integer intOf(String column) {
            String value = text(column);
            return value == null ? null : Integer.valueOf(value);
        }

        BigDecimal decimalOf(String column) {
            String value = text(column);
            return value == null ? null : new BigDecimal(value);
        }

        boolean boolOf(String column) {
            String value = text(column);
            return value != null && BOOLEAN_TRUE.contains(value.toLowerCase(Locale.ROOT));
        }

        LocalDate dateOf(String column) {
            String value = text(column);
            return value == null ? null : LocalDate.parse(value);
        }

        OffsetDateTime timestampOf(String column) {
            String value = text(column);
            return value == null ? null : OffsetDateTime.parse(value);
        }
    }

    // ------------------------------------------------------------------ 스펙

    private enum FieldType { TEXT, INT, DECIMAL, BOOLEAN, DATE, TIMESTAMP }

    /**
     * CSV 컬럼 하나의 정의.
     *
     * @param targetField      시스템 필드명. {@code null}이면 적재되지 않고 무시된다.
     * @param referencedTable  이 값이 가리키는 다른 테이블. {@code null}이면 FK가 아니다.
     */
    private record ColumnSpec(
            String csvColumn, String targetField, String description,
            boolean required, FieldType type, String referencedTable) {

        /** 이름이 {@code required}가 아닌 이유: 레코드 접근자 {@code required()}와 헷갈리기 때문이다. */
        static ColumnSpec mandatory(String csv, String target, String description, FieldType type) {
            return new ColumnSpec(csv, target, description, true, type, null);
        }

        static ColumnSpec optional(String csv, String target, String description, FieldType type) {
            return new ColumnSpec(csv, target, description, false, type, null);
        }

        static ColumnSpec foreignKey(String csv, String target, String description, String referencedTable) {
            return new ColumnSpec(csv, target, description, true, FieldType.TEXT, referencedTable);
        }

        /** 파일에는 있지만 적재하지 않는 컬럼. 무시된다는 사실을 화면에 드러내려고 스펙에 남긴다. */
        static ColumnSpec ignored(String csv, String description) {
            return new ColumnSpec(csv, null, description, false, FieldType.TEXT, null);
        }
    }

    private record TableSpec(
            String table, String label, int loadOrder,
            /** 파일 안 중복 판정 기준 컬럼. 없으면 중복 검사를 하지 않는다. */
            String businessKeyColumn,
            List<ColumnSpec> columns,
            BiFunction<ErpAdminService, RowView, ErpAdminDto.UpsertResponse> upsert) {}

    /**
     * ERP 10종 정의. 순서가 곧 적재 순서(FK 의존 순서)이며 {@code data/ERP_data/spring-csv}의
     * {@code 00_manifest.csv}와 같다.
     */
    private static final Map<String, TableSpec> TABLE_SPECS = buildSpecs();

    private static Map<String, TableSpec> buildSpecs() {
        Map<String, TableSpec> specs = new LinkedHashMap<>();

        specs.put("materials", new TableSpec("materials", "자재 마스터", 1, "material_id", List.of(
                ColumnSpec.mandatory("material_id", "erpMaterialId", "ERP 자재 ID", FieldType.TEXT),
                ColumnSpec.mandatory("material_code", "materialCode", "자재 코드", FieldType.TEXT),
                ColumnSpec.mandatory("material_name", "materialName", "자재명", FieldType.TEXT),
                ColumnSpec.mandatory("material_category", "materialCategory", "자재 분류", FieldType.TEXT),
                ColumnSpec.mandatory("base_unit", "baseUnit", "기본 단위", FieldType.TEXT),
                ColumnSpec.mandatory("criticality", "criticality", "중요도", FieldType.TEXT),
                ColumnSpec.mandatory("active", "active", "사용 여부", FieldType.BOOLEAN),
                ColumnSpec.mandatory("erp_group_code", "erpGroupCode", "ERP 그룹 코드", FieldType.TEXT),
                ColumnSpec.ignored("created_at", "생성 시각(적재 시 서버 시각 사용)"),
                ColumnSpec.ignored("updated_at", "수정 시각(적재 시 서버 시각 사용)")),
                (admin, row) -> admin.upsertMaterial(new ErpAdminDto.MaterialUpsertRequest(
                        row.text("material_id"), row.text("material_code"), row.text("material_name"),
                        row.text("material_category"), row.text("base_unit"), row.text("criticality"),
                        row.boolOf("active"), row.text("erp_group_code")))));

        specs.put("suppliers", new TableSpec("suppliers", "공급사", 2, "supplier_id", List.of(
                ColumnSpec.mandatory("supplier_id", "erpSupplierId", "ERP 공급사 ID", FieldType.TEXT),
                ColumnSpec.mandatory("supplier_code", "supplierCode", "공급사 코드", FieldType.TEXT),
                ColumnSpec.mandatory("supplier_name", "supplierName", "공급사명", FieldType.TEXT),
                ColumnSpec.mandatory("country_code", "countryCode", "국가 코드", FieldType.TEXT),
                ColumnSpec.mandatory("supplier_status", "supplierStatus", "거래 상태", FieldType.TEXT),
                ColumnSpec.mandatory("risk_level", "riskLevel", "위험 등급", FieldType.TEXT),
                ColumnSpec.mandatory("feoc_status", "feocStatus", "FEOC 해당 여부", FieldType.BOOLEAN),
                ColumnSpec.mandatory("certifications", "certifications", "보유 인증(세미콜론 구분)", FieldType.TEXT),
                ColumnSpec.ignored("created_at", "생성 시각(적재 시 서버 시각 사용)"),
                ColumnSpec.ignored("updated_at", "수정 시각(적재 시 서버 시각 사용)")),
                (admin, row) -> admin.upsertSupplier(new ErpAdminDto.SupplierUpsertRequest(
                        row.text("supplier_id"), row.text("supplier_code"), row.text("supplier_name"),
                        row.text("country_code"), row.text("supplier_status"), row.text("risk_level"),
                        row.boolOf("feoc_status"), row.text("certifications")))));

        specs.put("warehouses", new TableSpec("warehouses", "창고", 3, "warehouse_id", List.of(
                ColumnSpec.mandatory("warehouse_id", "erpWarehouseId", "ERP 창고 ID", FieldType.TEXT),
                ColumnSpec.mandatory("warehouse_code", "warehouseCode", "창고 코드", FieldType.TEXT),
                ColumnSpec.mandatory("warehouse_name", "warehouseName", "창고명", FieldType.TEXT),
                ColumnSpec.mandatory("country_code", "countryCode", "국가 코드", FieldType.TEXT),
                ColumnSpec.mandatory("timezone", "timezone", "시간대", FieldType.TEXT),
                ColumnSpec.mandatory("warehouse_type", "warehouseType", "창고 유형", FieldType.TEXT),
                ColumnSpec.mandatory("active", "active", "사용 여부", FieldType.BOOLEAN)),
                (admin, row) -> admin.upsertWarehouse(new ErpAdminDto.WarehouseUpsertRequest(
                        row.text("warehouse_id"), row.text("warehouse_code"), row.text("warehouse_name"),
                        row.text("country_code"), row.text("timezone"), row.text("warehouse_type"),
                        row.boolOf("active")))));

        specs.put("contracts", new TableSpec("contracts", "계약", 4, "contract_id", List.of(
                ColumnSpec.mandatory("contract_id", "erpContractId", "ERP 계약 ID", FieldType.TEXT),
                ColumnSpec.mandatory("contract_number", "contractNumber", "계약 번호", FieldType.TEXT),
                ColumnSpec.foreignKey("supplier_id", "erpSupplierId", "공급사 ERP ID", "suppliers"),
                ColumnSpec.foreignKey("material_id", "erpMaterialId", "자재 ERP ID", "materials"),
                ColumnSpec.mandatory("contract_name", "contractName", "계약명", FieldType.TEXT),
                ColumnSpec.mandatory("contract_status", "contractStatus", "계약 상태", FieldType.TEXT),
                ColumnSpec.mandatory("effective_date", "effectiveDate", "발효일", FieldType.DATE),
                ColumnSpec.optional("expiration_date", "expirationDate", "만료일", FieldType.DATE),
                ColumnSpec.mandatory("document_id", "documentId", "문서 ID", FieldType.TEXT),
                ColumnSpec.mandatory("document_source", "documentSource", "문서 출처", FieldType.TEXT),
                ColumnSpec.mandatory("document_path", "documentPath", "문서 경로", FieldType.TEXT),
                ColumnSpec.mandatory("contract_role", "contractRole", "계약 역할", FieldType.TEXT),
                ColumnSpec.mandatory("supplier_approval_status", "supplierApprovalStatus", "승인 상태", FieldType.TEXT),
                ColumnSpec.mandatory("indexed_at", "indexedAt", "색인 일시", FieldType.TIMESTAMP),
                ColumnSpec.ignored("updated_at", "수정 시각(적재 시 서버 시각 사용)")),
                (admin, row) -> admin.upsertContract(new ErpAdminDto.ContractUpsertRequest(
                        row.text("contract_id"), row.text("contract_number"), row.text("supplier_id"),
                        row.text("material_id"), row.text("contract_name"), row.text("contract_status"),
                        row.dateOf("effective_date"), row.dateOf("expiration_date"),
                        row.text("document_id"), row.text("document_source"), row.text("document_path"),
                        row.text("contract_role"), row.text("supplier_approval_status"),
                        row.timestampOf("indexed_at")))));

        specs.put("supplier_materials", new TableSpec(
                "supplier_materials", "공급사-자재", 5, "supplier_material_id", List.of(
                ColumnSpec.mandatory("supplier_material_id", "erpSupplierMaterialId", "공급관계 ID", FieldType.TEXT),
                ColumnSpec.foreignKey("supplier_id", "erpSupplierId", "공급사 ERP ID", "suppliers"),
                ColumnSpec.foreignKey("material_id", "erpMaterialId", "자재 ERP ID", "materials"),
                ColumnSpec.foreignKey("contract_id", "erpContractId", "계약 ERP ID", "contracts"),
                ColumnSpec.mandatory("supply_share_ratio", "supplyShareRatio", "공급 비중", FieldType.DECIMAL),
                ColumnSpec.mandatory("lead_time_days", "leadTimeDays", "리드타임(일)", FieldType.INT),
                ColumnSpec.mandatory("minimum_order_quantity", "minimumOrderQuantity", "최소 주문량", FieldType.DECIMAL),
                ColumnSpec.mandatory("approved_status", "approvedStatus", "승인 상태", FieldType.TEXT),
                ColumnSpec.mandatory("priority_rank", "priorityRank", "우선순위", FieldType.INT),
                ColumnSpec.mandatory("is_alternative", "isAlternative", "대체 공급사 여부", FieldType.BOOLEAN),
                ColumnSpec.mandatory("valid_from", "validFrom", "유효 시작일", FieldType.DATE),
                ColumnSpec.optional("valid_to", "validTo", "유효 종료일", FieldType.DATE),
                // upsertSupplierMaterial의 INSERT에 이 컬럼이 없다. 값을 넣어도 반영되지 않는다.
                ColumnSpec.ignored("available_capacity_quantity", "가용 생산능력(현재 적재 대상 아님)")),
                (admin, row) -> admin.upsertSupplierMaterial(new ErpAdminDto.SupplierMaterialUpsertRequest(
                        row.text("supplier_material_id"), row.text("supplier_id"), row.text("material_id"),
                        row.text("contract_id"), row.decimalOf("supply_share_ratio"),
                        row.intOf("lead_time_days"), row.decimalOf("minimum_order_quantity"),
                        row.text("approved_status"), row.intOf("priority_rank"),
                        row.boolOf("is_alternative"), row.dateOf("valid_from"), row.dateOf("valid_to")))));

        specs.put("inventory_snapshots", new TableSpec(
                "inventory_snapshots", "재고 스냅샷", 6, "inventory_snapshot_id", List.of(
                ColumnSpec.mandatory("inventory_snapshot_id", "erpInventorySnapshotId", "스냅샷 ID", FieldType.TEXT),
                ColumnSpec.foreignKey("material_id", "erpMaterialId", "자재 ERP ID", "materials"),
                ColumnSpec.foreignKey("warehouse_id", "erpWarehouseId", "창고 ERP ID", "warehouses"),
                ColumnSpec.mandatory("on_hand_quantity", "onHandQuantity", "보유 재고", FieldType.DECIMAL),
                ColumnSpec.mandatory("reserved_quantity", "reservedQuantity", "예약 재고", FieldType.DECIMAL),
                ColumnSpec.mandatory("blocked_quantity", "blockedQuantity", "차단 재고", FieldType.DECIMAL),
                ColumnSpec.mandatory("quality_hold_quantity", "qualityHoldQuantity", "품질 보류", FieldType.DECIMAL),
                ColumnSpec.mandatory("safety_stock_quantity", "safetyStockQuantity", "안전 재고", FieldType.DECIMAL),
                ColumnSpec.mandatory("snapshot_at", "snapshotAt", "스냅샷 시각", FieldType.TIMESTAMP),
                ColumnSpec.mandatory("is_current", "isCurrent", "현재 여부", FieldType.BOOLEAN),
                ColumnSpec.mandatory("source_unit", "sourceUnit", "원본 단위", FieldType.TEXT),
                ColumnSpec.mandatory("normalized_unit", "normalizedUnit", "정규화 단위", FieldType.TEXT),
                ColumnSpec.mandatory("data_quality_flag", "dataQualityFlag", "데이터 품질 플래그", FieldType.TEXT)),
                (admin, row) -> admin.importInventorySnapshot(new ErpAdminDto.InventorySnapshotImportRequest(
                        row.text("inventory_snapshot_id"), row.text("material_id"), row.text("warehouse_id"),
                        row.decimalOf("on_hand_quantity"), row.decimalOf("reserved_quantity"),
                        row.decimalOf("blocked_quantity"), row.decimalOf("quality_hold_quantity"),
                        row.decimalOf("safety_stock_quantity"), row.timestampOf("snapshot_at"),
                        row.boolOf("is_current"), row.text("source_unit"), row.text("normalized_unit"),
                        row.text("data_quality_flag")))));

        specs.put("material_consumptions", new TableSpec(
                "material_consumptions", "자재 소비량", 7, "consumption_id", List.of(
                ColumnSpec.mandatory("consumption_id", "erpConsumptionId", "소비량 ID", FieldType.TEXT),
                ColumnSpec.foreignKey("material_id", "erpMaterialId", "자재 ERP ID", "materials"),
                ColumnSpec.mandatory("plant_code", "plantCode", "공장 코드", FieldType.TEXT),
                ColumnSpec.optional("average_daily_usage", "averageDailyUsage", "일평균 사용량", FieldType.DECIMAL),
                ColumnSpec.mandatory("calculation_window_days", "calculationWindowDays", "산출 기간(일)", FieldType.INT),
                ColumnSpec.mandatory("calculated_at", "calculatedAt", "산출 시각", FieldType.TIMESTAMP),
                ColumnSpec.mandatory("is_current", "isCurrent", "현재 여부", FieldType.BOOLEAN),
                ColumnSpec.mandatory("data_quality_flag", "dataQualityFlag", "데이터 품질 플래그", FieldType.TEXT)),
                (admin, row) -> admin.importMaterialConsumption(new ErpAdminDto.MaterialConsumptionImportRequest(
                        row.text("consumption_id"), row.text("material_id"), row.text("plant_code"),
                        row.decimalOf("average_daily_usage"), row.intOf("calculation_window_days"),
                        row.timestampOf("calculated_at"), row.boolOf("is_current"),
                        row.text("data_quality_flag")))));

        specs.put("purchase_orders", new TableSpec(
                "purchase_orders", "발주", 8, "purchase_order_id", List.of(
                ColumnSpec.mandatory("purchase_order_id", "erpPurchaseOrderId", "발주 ERP ID", FieldType.TEXT),
                ColumnSpec.mandatory("po_number", "poNumber", "발주 번호", FieldType.TEXT),
                ColumnSpec.foreignKey("supplier_id", "erpSupplierId", "공급사 ERP ID", "suppliers"),
                ColumnSpec.mandatory("order_date", "orderDate", "발주일", FieldType.DATE),
                ColumnSpec.mandatory("currency", "currency", "통화", FieldType.TEXT),
                ColumnSpec.mandatory("order_status", "orderStatus", "발주 상태", FieldType.TEXT),
                ColumnSpec.mandatory("transport_mode", "transportMode", "운송 수단", FieldType.TEXT),
                ColumnSpec.optional("port_of_entry", "portOfEntry", "입항지", FieldType.TEXT),
                ColumnSpec.ignored("created_at", "생성 시각(적재 시 서버 시각 사용)"),
                ColumnSpec.ignored("updated_at", "수정 시각(적재 시 서버 시각 사용)")),
                (admin, row) -> admin.upsertPurchaseOrder(new ErpAdminDto.PurchaseOrderUpsertRequest(
                        row.text("purchase_order_id"), row.text("po_number"), row.text("supplier_id"),
                        row.dateOf("order_date"), row.text("currency"), row.text("order_status"),
                        row.text("transport_mode"), row.text("port_of_entry")))));

        specs.put("purchase_order_items", new TableSpec(
                "purchase_order_items", "발주 품목", 9, "purchase_order_item_id", List.of(
                ColumnSpec.mandatory("purchase_order_item_id", "erpPurchaseOrderItemId", "발주 품목 ID", FieldType.TEXT),
                ColumnSpec.foreignKey("purchase_order_id", "erpPurchaseOrderId", "발주 ERP ID", "purchase_orders"),
                ColumnSpec.foreignKey("material_id", "erpMaterialId", "자재 ERP ID", "materials"),
                ColumnSpec.foreignKey("contract_id", "erpContractId", "계약 ERP ID", "contracts"),
                ColumnSpec.mandatory("ordered_quantity", "orderedQuantity", "발주 수량", FieldType.DECIMAL),
                ColumnSpec.mandatory("received_quantity", "receivedQuantity", "입고 수량", FieldType.DECIMAL),
                ColumnSpec.mandatory("unit", "unit", "단위", FieldType.TEXT),
                ColumnSpec.optional("expected_arrival_date", "expectedArrivalDate", "도착 예정일", FieldType.DATE),
                ColumnSpec.optional("confirmed_arrival_date", "confirmedArrivalDate", "도착 확정일", FieldType.DATE),
                ColumnSpec.mandatory("unit_price", "unitPrice", "단가", FieldType.DECIMAL),
                ColumnSpec.mandatory("incoterm", "incoterm", "인코텀즈", FieldType.TEXT),
                ColumnSpec.foreignKey("destination_warehouse_id", "erpWarehouseId", "도착 창고 ERP ID", "warehouses")),
                (admin, row) -> admin.upsertPurchaseOrderItem(new ErpAdminDto.PurchaseOrderItemUpsertRequest(
                        row.text("purchase_order_item_id"), row.text("purchase_order_id"),
                        row.text("material_id"), row.text("contract_id"),
                        row.decimalOf("ordered_quantity"), row.decimalOf("received_quantity"),
                        row.text("unit"), row.dateOf("expected_arrival_date"),
                        row.dateOf("confirmed_arrival_date"), row.decimalOf("unit_price"),
                        row.text("incoterm"), row.text("destination_warehouse_id")))));

        specs.put("goods_receipts", new TableSpec(
                "goods_receipts", "입고", 10, "goods_receipt_id", List.of(
                ColumnSpec.mandatory("goods_receipt_id", "erpGoodsReceiptId", "입고 ERP ID", FieldType.TEXT),
                ColumnSpec.foreignKey("purchase_order_item_id", "erpPurchaseOrderItemId",
                        "발주 품목 ERP ID", "purchase_order_items"),
                ColumnSpec.mandatory("received_quantity", "receivedQuantity", "입고 수량", FieldType.DECIMAL),
                ColumnSpec.mandatory("received_at", "receivedAt", "입고 일시", FieldType.TIMESTAMP),
                ColumnSpec.foreignKey("warehouse_id", "erpWarehouseId", "창고 ERP ID", "warehouses"),
                ColumnSpec.mandatory("quality_status", "qualityStatus", "품질 상태", FieldType.TEXT),
                ColumnSpec.mandatory("batch_number", "batchNumber", "배치 번호", FieldType.TEXT),
                ColumnSpec.optional("quality_released_at", "qualityReleasedAt", "품질 승인 일시", FieldType.TIMESTAMP)),
                (admin, row) -> admin.upsertGoodsReceipt(new ErpAdminDto.GoodsReceiptUpsertRequest(
                        row.text("goods_receipt_id"), row.text("purchase_order_item_id"),
                        row.decimalOf("received_quantity"), row.timestampOf("received_at"),
                        row.text("warehouse_id"), row.text("quality_status"),
                        row.text("batch_number"), row.timestampOf("quality_released_at")))));

        // Map.copyOf가 아니라 unmodifiableMap인 이유: copyOf는 순서를 버린다. 여기 순서는
        // FK 의존 순서(manifest 순서)라서, detect()의 후보 비교가 실행마다 달라지면 안 된다.
        return Collections.unmodifiableMap(specs);
    }

    private record DataRow(int lineNumber, Map<String, String> values) {}

    private record ParsedFile(
            String fileName, long sizeBytes, TableSpec spec,
            List<String> headers, List<DataRow> rows,
            List<ErpImportDto.Issue> fileIssues) {}
}

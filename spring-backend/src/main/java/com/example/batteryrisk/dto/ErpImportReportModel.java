package com.example.batteryrisk.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * ERP 적재 검증 보고서(PDF)의 내용 모델.
 *
 * <p>{@link ErpImportDto.PreviewResponse}를 그대로 쓰지 않고 한 겹 두는 이유는 두 가지다.
 * 첫째, 화면 응답은 파일별로 나뉘어 있는데 보고서는 "전체 오류를 심각도 순으로" 같은 <b>가로로
 * 합친 표</b>가 필요하다. 둘째, 최종 반영 보고서에는 화면 응답에 없는 승인 정보({@link Commit})가
 * 붙는다. 렌더링 코드가 이 조립을 매번 다시 하면 표마다 기준이 어긋난다.
 *
 * <p>이 모델은 <b>표시할 값만</b> 담는다. 여기 없는 숫자는 PDF에도 없다 — 렌더러가 계산을
 * 하지 않게 해서 "화면과 보고서의 수치가 다르다"는 사고를 구조적으로 막는다.
 */
public record ErpImportReportModel(
        /** PRE_COMMIT | POST_COMMIT */
        String reportType,
        OffsetDateTime generatedAt,
        String systemName,
        /** 검사 담당자 — 보고서를 요청한 로그인 사용자. */
        String inspector,
        Overview overview,
        List<FileRow> files,
        List<TableEstimate> tableEstimates,
        List<MappingRow> mappings,
        /** 심각도 순 상위 N건. 전체 건수는 {@link Overview}의 합계를 본다. */
        List<IssueRow> issues,
        /** 잘라내기 전 전체 이슈 수. {@code issues.size()}보다 크면 안내문을 넣는다. */
        int totalIssueCount,
        /** POST_COMMIT일 때만 채워진다. 반영 전 보고서에서는 null. */
        Commit commit
) {
    /** 검증 개요 — 표지 다음 첫 장에 들어가는 요약. */
    public record Overview(
            int fileCount,
            int totalRows,
            int totalErrors,
            int totalWarnings,
            int totalDuplicates,
            int qualityScore,
            boolean committable
    ) {}

    /** 파일별 검증 결과 한 줄. */
    public record FileRow(
            String fileName,
            String targetLabel,
            long sizeBytes,
            int rowCount,
            int errorCount,
            int warningCount,
            int duplicateCount,
            /** SUCCESS | WARNING | ERROR */
            String result
    ) {}

    /** 테이블별 예상 반영 행 수. */
    public record TableEstimate(String targetTable, String label, int rowCount) {}

    /**
     * 스키마 매핑 한 줄. 화면과 달리 파일명을 함께 싣는다 — 보고서는 여러 파일의 매핑을 한 표에
     * 이어 붙이므로, 파일명이 없으면 어느 파일의 컬럼인지 알 수 없다.
     */
    public record MappingRow(
            String fileName,
            String sourceColumn,
            String targetField,
            String description,
            boolean required,
            /** MAPPED | IGNORED | MISSING */
            String status,
            String sample
    ) {}

    /** 오류·경고 한 줄. */
    public record IssueRow(
            String fileName,
            /** ERROR | WARNING | DUPLICATE */
            String level,
            Integer rowNumber,
            String column,
            String message
    ) {}

    /** 최종 반영 결과. 서명된 영수증에서만 나온다 — 프론트가 준 숫자는 여기 들어오지 않는다. */
    public record Commit(
            String approver,
            OffsetDateTime committedAt,
            int totalInserted,
            int totalUpdated,
            List<TableResultRow> results,
            /** null이 아니면 "DB는 반영됐지만 KG는 못 따라갔다"는 뜻이다. */
            String kgSyncWarning
    ) {}

    /** 테이블별 실제 처리 결과. */
    public record TableResultRow(String targetTable, String label, int inserted, int updated) {}
}

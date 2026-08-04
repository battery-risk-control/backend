package com.example.batteryrisk.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 구매팀 "데이터 관리" 화면의 ERP CSV 일괄 적재(분석 → DB 반영)에서 쓰는 DTO 모음입니다.
 *
 * <p>계약서 업로드({@link ContractUploadDto})와 같은 2단계 구조다 — {@code /preview}는 DB에
 * 아무것도 쓰지 않고 분석 결과만 돌려주고, {@code /commit}에서 실제로 적재한다. 그래서 잡 ID나
 * 스테이징 테이블이 없다. 두 호출 모두 파일을 그대로 받는다(프론트가 File을 들고 있다가 재전송).
 *
 * <p>Jackson 전역 설정이 SNAKE_CASE라 필드명은 camelCase로 두어도 {@code target_table}처럼 나간다.
 */
public final class ErpImportDto {
    private ErpImportDto() {}

    /**
     * 화면이 파일 선택 단계에서 미리 걸러줄 제약. 서버 설정({@code app.upload.max-file-size})이
     * 진실이고 프론트는 그걸 표시만 한다 — 프론트에 숫자를 적어두면 설정을 바꿨을 때 "화면은
     * 통과인데 서버가 거부하는" 상태가 된다.
     */
    public record UploadConstraints(
            long maxFileSizeBytes,
            List<String> allowedExtensions
    ) {}

    /** 업로드 파일 한 개의 분석 결과. 화면의 "데이터 검증 결과" 표 한 줄에 대응한다. */
    public record FileAnalysis(
            String fileName,
            /** 판별된 대상 테이블. 판별에 실패하면 null이고 result는 ERROR가 된다. */
            String targetTable,
            /** 화면 표시용 한글 라벨(예: "자재 마스터"). 미판별이면 null. */
            String targetLabel,
            /** 적재 순서(1~10). FK 의존 순서이며 commit이 이 순서로 정렬해 적재한다. 미판별이면 0. */
            int loadOrder,
            long sizeBytes,
            int rowCount,
            int columnCount,
            /** SUCCESS | WARNING | ERROR — 화면 배지에 그대로 쓴다. */
            String result,
            int errorCount,
            int warningCount,
            int duplicateCount,
            /** 업로드 파일 헤더 ↔ 시스템 필드 대응. 화면의 "스키마 매핑" 표. */
            List<ColumnMapping> columns,
            /** 상위 몇 행만 담은 미리보기. 화면의 "내용 분석" 칸이 이걸 표로 그린다. */
            List<Map<String, String>> sampleRows,
            List<Issue> issues
    ) {}

    /**
     * 컬럼 한 개의 매핑 상태.
     *
     * <ul>
     *   <li>{@code MAPPED} — 시스템 필드로 들어간다
     *   <li>{@code IGNORED} — 파일에는 있지만 적재되지 않는다(예: {@code created_at},
     *       {@code available_capacity_quantity}). 조용히 버리면 데이터가 사라진 걸 알 수 없어서
     *       화면에 "무시됨"으로 드러낸다.
     *   <li>{@code MISSING} — 시스템이 요구하는데 파일에 없다
     * </ul>
     */
    public record ColumnMapping(
            String sourceColumn,
            String targetField,
            String description,
            boolean required,
            String sample,
            String status
    ) {}

    /** 검증에서 걸린 항목 하나. rowNumber가 null이면 파일 전체에 대한 지적이다. */
    public record Issue(
            /** ERROR | WARNING | DUPLICATE */
            String level,
            /** 헤더를 1행으로 세는 실제 파일 줄 번호. 파일 단위 이슈면 null. */
            Integer rowNumber,
            String column,
            String message
    ) {}

    /** 대상 테이블별 적재 예정 건수. 화면의 "가져오기 요약". */
    public record TableCount(
            String targetTable,
            String label,
            int rowCount
    ) {}

    /** 1단계 분석 응답 — DB에는 아무것도 쓰지 않는다. */
    public record PreviewResponse(
            List<FileAnalysis> files,
            int totalRows,
            int totalErrors,
            int totalWarnings,
            int totalDuplicates,
            /** 0~100. 오류/경고/중복 비율을 가중해 깎은 점수. */
            int qualityScore,
            /** ERROR가 하나라도 있으면 false — 화면의 "DB에 반영" 버튼을 잠근다. */
            boolean committable,
            List<TableCount> summary
    ) {}

    /** 테이블별 적재 결과. */
    public record TableResult(
            String targetTable,
            String label,
            int inserted,
            int updated
    ) {}

    /** 2단계 반영 응답. */
    public record CommitResponse(
            OffsetDateTime committedAt,
            int totalInserted,
            int totalUpdated,
            List<TableResult> results,

            /**
             * KG 동기화 실패 사유. 재고·소비량 행이 kg_service로 흘러가지 못했을 때만 채워진다.
             *
             * <p>적재는 성공했는데 지식그래프만 이전 값으로 남는 상태를 화면이 알려야 한다 —
             * 그러지 않으면 그 뒤 부족 판정이 방금 올린 재고를 반영하지 못하는 걸 아무도 모른다.
             * 같은 사유가 수백 행에서 반복되므로 첫 건만 싣는다.
             */
            String kgSyncWarning,

            /**
             * 서명된 적재 영수증. 최종 반영 보고서(PDF)를 받을 때 그대로 되돌려주면 된다.
             *
             * <p>화면에 보여줄 값이 아니다. 위 숫자들을 서버가 서명해 담아둔 것으로, 보고서에
             * 찍히는 "누가 언제 몇 건" 을 브라우저가 고칠 수 없게 만드는 게 목적이다.
             * 자세한 이유는 {@code ErpImportReceiptService} 참고.
             */
            String receipt
    ) {}
}

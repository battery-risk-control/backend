package com.example.batteryrisk;

import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.repository.ErpRepository;
import com.example.batteryrisk.service.ErpAdminService;
import com.example.batteryrisk.service.ErpImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 데이터 관리 화면의 ERP CSV 분석({@code /preview}) 검증. 적재는 {@link ErpAdminService}에
 * 위임하므로 여기서는 파싱·판별·검증만 본다 — mock을 세워두고 DB는 건드리지 않는다.
 */
class ErpImportServiceTest {
    /** 팀이 실제로 올리게 될 표준 CSV 묶음. 리포지토리 루트 기준 경로다. */
    private static final Path SEED_DIRECTORY = Path.of("../data/ERP_data/spring-csv");

    private final ErpRepository repository = mock(ErpRepository.class);
    private final ErpImportService service =
            new ErpImportService(mock(ErpAdminService.class), repository, 52_428_800L);

    @Test
    void standardSeedPackagePassesValidation() throws IOException {
        assumeTrue(Files.isDirectory(SEED_DIRECTORY), "ERP 시드 CSV 디렉터리가 없어 건너뜁니다");
        List<MultipartFile> files = loadSeedFiles();
        assumeTrue(files.size() == 10, "시드 CSV 10개가 모두 있어야 합니다");

        ErpImportDto.PreviewResponse preview = service.preview(files);

        // 10개 파일이 각각 자기 테이블로 판별되고, 오류 없이 반영 가능해야 한다.
        assertEquals(10, preview.files().size());
        for (ErpImportDto.FileAnalysis analysis : preview.files()) {
            assertEquals(0, analysis.errorCount(),
                    analysis.fileName() + " 오류: " + analysis.issues());
            assertTrue(analysis.targetTable() != null, analysis.fileName() + " 대상 테이블 미판별");
        }
        assertTrue(preview.committable());
        assertEquals(0, preview.totalErrors());
        assertEquals(10, preview.summary().size());
        // 요약은 FK 의존 순서(자재 → 공급사 → …)로 나와야 화면에서 적재 순서를 그대로 읽을 수 있다.
        assertEquals("materials", preview.summary().get(0).targetTable());
        assertEquals("goods_receipts", preview.summary().get(9).targetTable());
    }

    /**
     * 신규 자재를 참조하는 계약을 자재 파일과 <b>함께</b> 올리면 통과해야 한다. DB만 보고
     * 판단하면 "자재 먼저 올리고 계약을 따로" 말고는 방법이 없어져, 정상 사용이 막힌다.
     */
    @Test
    void foreignKeyResolvesAgainstFilesInTheSameBatch() {
        MultipartFile materials = csv("01_materials.csv", """
                material_id,material_code,material_name,material_category,base_unit,criticality,active,erp_group_code
                MAT-NEW,RM-NEW-001,New Material,LITHIUM,KG,HIGH,true,BATT-99
                """);
        MultipartFile contracts = csv("04_contracts.csv", """
                contract_id,contract_number,supplier_id,material_id,contract_name,contract_status,effective_date,expiration_date,document_id,document_source,document_path,contract_role,supplier_approval_status,indexed_at
                CTR-900,BA-2026-0900,SUP-NEW,MAT-NEW,New Supply Agreement,ACTIVE,2026-01-01,2027-01-01,DOC-900,MOCK_INTERNAL,contracts/CTR-900.pdf,PRIMARY,APPROVED,2026-06-22T00:00:00+09:00
                """);
        MultipartFile suppliers = csv("02_suppliers.csv", """
                supplier_id,supplier_code,supplier_name,country_code,supplier_status,risk_level,feoc_status,certifications
                SUP-NEW,NEW-1001,New Supplier,AU,ACTIVE,NORMAL,false,ISO9001
                """);

        ErpImportDto.PreviewResponse preview = service.preview(List.of(contracts, materials, suppliers));

        assertEquals(0, preview.totalErrors(), preview.files().toString());
        assertTrue(preview.committable());
    }

    /** 같은 계약을 자재 없이 혼자 올리면 FK가 풀리지 않아 오류로 잡혀야 한다. */
    @Test
    void foreignKeyMissingEverywhereIsReportedAsError() {
        MultipartFile contracts = csv("04_contracts.csv", """
                contract_id,contract_number,supplier_id,material_id,contract_name,contract_status,effective_date,expiration_date,document_id,document_source,document_path,contract_role,supplier_approval_status,indexed_at
                CTR-900,BA-2026-0900,SUP-NEW,MAT-NEW,New Supply Agreement,ACTIVE,2026-01-01,2027-01-01,DOC-900,MOCK_INTERNAL,contracts/CTR-900.pdf,PRIMARY,APPROVED,2026-06-22T00:00:00+09:00
                """);

        ErpImportDto.PreviewResponse preview = service.preview(List.of(contracts));

        assertEquals(2, preview.totalErrors());   // supplier_id, material_id 둘 다
        assertFalse(preview.committable());
    }

    @Test
    void missingRequiredColumnAndBadTypesAreReported() {
        // material_name(필수) 없음, active에 불리언이 아닌 값, 마지막 줄은 키 중복
        MultipartFile broken = csv("01_materials.csv", """
                material_id,material_code,material_category,base_unit,criticality,active,erp_group_code
                MAT-A,RM-001,LITHIUM,KG,HIGH,maybe,BATT-01
                MAT-A,RM-002,LITHIUM,KG,HIGH,true,BATT-02
                """);

        ErpImportDto.PreviewResponse preview = service.preview(List.of(broken));
        ErpImportDto.FileAnalysis analysis = preview.files().get(0);

        assertEquals("materials", analysis.targetTable());
        assertEquals("ERROR", analysis.result());
        assertEquals(1, analysis.duplicateCount());
        assertFalse(preview.committable());
        assertTrue(analysis.issues().stream().anyMatch(
                issue -> "material_name".equals(issue.column()) && "ERROR".equals(issue.level())));
        assertTrue(analysis.issues().stream().anyMatch(
                issue -> "active".equals(issue.column()) && issue.message().contains("true/false")));
    }

    /** 적재되지 않는 컬럼은 경고로 드러나야 한다 — 조용히 버리면 값이 반영된 줄 안다. */
    @Test
    void ignoredColumnIsSurfacedAsWarning() {
        MultipartFile file = csv("05_supplier_materials.csv", """
                supplier_material_id,supplier_id,material_id,supply_share_ratio,lead_time_days,minimum_order_quantity,approved_status,priority_rank,is_alternative,valid_from,valid_to,contract_id,available_capacity_quantity
                SM-001,SUP-A,MAT-A,0.45,15,900,APPROVED,1,false,2025-01-28,2027-07-22,CTR-001,1200
                """);

        ErpImportDto.FileAnalysis analysis = service.preview(List.of(file)).files().get(0);

        assertTrue(analysis.columns().stream().anyMatch(column ->
                "available_capacity_quantity".equals(column.sourceColumn())
                        && "IGNORED".equals(column.status())));
        assertTrue(analysis.issues().stream().anyMatch(issue ->
                "available_capacity_quantity".equals(issue.column()) && "WARNING".equals(issue.level())));
    }

    /** 국내 엑셀이 저장하는 MS949 CSV도 읽혀야 한다(시드 경로와 같은 폴백). */
    @Test
    void ms949EncodedCsvIsDecoded() {
        byte[] ms949 = ("""
                material_id,material_code,material_name,material_category,base_unit,criticality,active,erp_group_code
                MAT-A,RM-001,양극재,LITHIUM,KG,HIGH,true,BATT-01
                """).getBytes(Charset.forName("MS949"));

        ErpImportDto.FileAnalysis analysis = service.preview(List.of(
                new MockMultipartFile("files", "01_materials.csv", "text/csv", ms949))).files().get(0);

        assertEquals(0, analysis.errorCount());
        assertEquals("양극재", analysis.sampleRows().get(0).get("material_name"));
    }

    /** CSV가 아닌 파일은 ERP 모드에서 받지 않는다. */
    @Test
    void nonCsvIsRejected() {
        ErpImportDto.FileAnalysis analysis = service.preview(List.of(
                new MockMultipartFile("files", "contract.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .files().get(0);

        assertEquals("ERROR", analysis.result());
        assertTrue(analysis.issues().get(0).message().contains("CSV"));
    }

    private static MultipartFile csv(String fileName, String body) {
        return new MockMultipartFile("files", fileName, "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private static List<MultipartFile> loadSeedFiles() throws IOException {
        List<MultipartFile> files = new ArrayList<>();
        try (var paths = Files.list(SEED_DIRECTORY)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".csv") || name.startsWith("00_")) continue;   // manifest는 적재 대상이 아니다
                files.add(new MockMultipartFile("files", name, "text/csv", Files.readAllBytes(path)));
            }
        }
        return files;
    }
}

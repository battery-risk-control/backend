package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.service.ErpImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 구매팀 "데이터 관리" 화면의 ERP CSV 일괄 적재 API.
 *
 * <p>{@link ContractUploadController}와 같은 2단계다 — {@code /preview}는 DB를 건드리지 않고
 * 분석 결과만 주고, {@code /commit}에서 실제로 반영한다. 두 호출 모두 파일을 그대로 받으므로
 * 잡 ID를 주고받지 않는다(프론트가 File을 들고 있다가 재전송).
 *
 * <p>{@link ErpAdminController}(단건 Upsert)가 로그인한 모든 계층에 열려 있는 것과 달리 여기는
 * 구매팀 전용이다. 한 번에 수백 행의 ERP 원본을 덮어쓰는 화면이라 접근 범위를 좁혔다.
 */
@RestController
@RequestMapping("/api/v1/erp/imports")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('PURCHASING')")
public class ErpImportController {
    private final ErpImportService service;

    public ErpImportController(ErpImportService service) {
        this.service = service;
    }

    @Operation(
            summary = "업로드 제약 조회",
            description = "화면이 파일을 고르기 전에 막아줄 수 있도록 허용 확장자와 최대 크기를 알려줍니다. "
                    + "프론트가 값을 하드코딩하면 서버 설정을 바꿨을 때 두 값이 어긋납니다.")
    @GetMapping("/constraints")
    public ApiResponse<ErpImportDto.UploadConstraints> constraints() {
        return ApiResponse.ok(new ErpImportDto.UploadConstraints(
                service.maxFileSize(), List.of(".csv")));
    }

    @Operation(
            summary = "ERP CSV 분석 (DB 미반영)",
            description = "올린 CSV의 대상 테이블을 판별하고 컬럼 매핑·행 검증 결과와 미리보기 행을 돌려줍니다. "
                    + "DB에는 아무것도 쓰지 않습니다.")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ErpImportDto.PreviewResponse> preview(
            @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(service.preview(files));
    }

    @Operation(
            summary = "ERP CSV 반영",
            description = "분석과 같은 검증을 다시 돌린 뒤 FK 의존 순서대로 적재합니다. "
                    + "한 트랜잭션이라 한 행이라도 실패하면 전부 되돌립니다. 오류가 하나라도 남아 있으면 거부합니다.")
    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ErpImportDto.CommitResponse> commit(
            @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(service.commit(files));
    }
}

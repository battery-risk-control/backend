package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.dto.CollectionDto;
import com.example.batteryrisk.service.CollectionService;
import com.example.batteryrisk.service.HistoricalDataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collection")
@SecurityRequirement(name = "bearerAuth")
public class CollectionController {
    private final CollectionService collectionService;
    private final HistoricalDataImportService historicalDataImportService;

    public CollectionController(
            CollectionService collectionService, HistoricalDataImportService historicalDataImportService) {
        this.collectionService = collectionService;
        this.historicalDataImportService = historicalDataImportService;
    }

    @Operation(summary = "전체 데이터 소스 수동 수집", description = "Scheduler 주기를 기다리지 않고 등록된 모든 Adapter를 즉시 실행합니다.")
    @PostMapping("/run")
    public ApiResponse<CollectionDto.CollectionSummary> runAll() {
        return ApiResponse.ok(collectionService.runAll());
    }

    @Operation(summary = "단일 데이터 소스 수동 수집", description = "지정한 source(GDELT, GDACS) 하나만 즉시 실행합니다.")
    @PostMapping("/run/{source}")
    public ApiResponse<CollectionDto.CollectionRunResult> runSource(@PathVariable String source) {
        return ApiResponse.ok(collectionService.runSource(source));
    }

    @Operation(summary = "과거 아카이브 대량 적재", description = "BDI/GDACS 과거 이력 로컬 아카이브를 raw_events에 일괄 적재합니다. 경로는 app.historical-data.extra-path 설정을 따릅니다.")
    @PostMapping("/import-historical")
    public ApiResponse<CollectionDto.CollectionSummary> importHistorical() {
        return ApiResponse.ok(historicalDataImportService.importAll());
    }

    @Operation(
            summary = "테스트용 뉴스 이벤트로 F4→F3 파이프라인 검증",
            description = "GDELT 실시간 수집이 막혀있을 때, title/content/country_code로 가짜 뉴스 이벤트를 만들어 "
                    + "실제 운영 코드와 동일한 경로(raw_events 저장 → 피처 조인 → FastAPI 분석 호출)를 그대로 검증합니다. "
                    + "응답의 analysis_id로 GET /api/v1/analyses/{analysis_id}를 호출하면 최종 분석 결과를 볼 수 있습니다."
    )
    @PostMapping("/test-news")
    public ApiResponse<CollectionDto.TestNewsResult> testNews(@RequestBody CollectionDto.TestNewsRequest request) {
        return ApiResponse.ok(collectionService.triggerTestNews(
                request.title(), request.content(), request.countryCode(), request.sourceUrl()));
    }
}

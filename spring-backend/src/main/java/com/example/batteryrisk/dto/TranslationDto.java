package com.example.batteryrisk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 영문 뉴스 제목 → 한국어 번역(F4 부속) 요청·응답 DTO.
 *
 * <p>FastAPI와 주고받는 타입에는 {@code @JsonProperty}로 snake_case를 명시한다 — FastAPI 호출에 쓰는
 * RestClient는 전역 SNAKE_CASE 설정을 타지 않기 때문이다(CollectionDto와 같은 방침).
 * 반대로 {@link TranslationRunResult}는 Spring이 직접 내려주는 응답이라 전역 설정이 적용되므로
 * 애노테이션이 필요 없다.
 */
public final class TranslationDto {
    private TranslationDto() {}

    /** 번역 대상 1건. id는 {@code raw_events.id}를 문자열로 담아 결과를 되짚는 데만 쓴다. */
    public record TitleToTranslate(String id, String title) {}

    public record TranslateTitlesRequest(List<TitleToTranslate> items) {}

    public record TranslatedTitle(String id, @JsonProperty("title_ko") String titleKo) {}

    public record TranslateTitlesResult(
            List<TranslatedTitle> items,
            @JsonProperty("model_version") String modelVersion,
            boolean mock
    ) {}

    public record FastApiTranslateResponse(boolean success, TranslateTitlesResult data) {}

    /** 번역 1회 실행 결과(수동 트리거 응답·로그용). */
    public record TranslationRunResult(
            @Schema(description = "이번 주기에 집어온 미번역 건수") int picked,
            @Schema(description = "실제로 번역되어 저장된 건수") int translated,
            @Schema(description = "SUCCESS / DISABLED / FAILED") String status,
            String errorMessage
    ) {}
}

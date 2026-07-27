package com.example.batteryrisk.dto;

import java.util.List;

/**
 * 목록 API 공통 페이지 응답.
 *
 * <p>S14 공통 규칙에 따라 모든 목록 API가 같은 형태를 사용한다.
 * JSON에서는 `snake_case`로 내려간다(`total_elements`, `total_pages`).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}

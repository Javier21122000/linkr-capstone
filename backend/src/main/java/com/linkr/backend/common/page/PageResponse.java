package com.linkr.backend.common.page;

import java.util.List;

/** Risposta paginata stabile (non si espone il Page di Spring, il cui JSON non e' garantito stabile). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
		int totalPages = (int) ((totalElements + size - 1) / size);
		return new PageResponse<>(items, page, size, totalElements, totalPages);
	}

}

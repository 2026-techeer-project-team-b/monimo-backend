package com.monimo.api.common.web

import com.fasterxml.jackson.annotation.JsonInclude

// 성공 응답 봉투 (API 명세 §0). 단건은 { data }, 목록은 { data, page }
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val data: T,
    val page: PageInfo? = null,
) {
    companion object {
        fun <T> of(data: T) = ApiResponse(data)

        fun <T> page(data: List<T>, nextCursor: String?, limit: Int) = ApiResponse(data, PageInfo(nextCursor, limit))
    }
}

// 마지막 쪽이면 next_cursor 는 빠지지 않고 null 로 나간다
data class PageInfo(
    val nextCursor: String?,
    val limit: Int,
)

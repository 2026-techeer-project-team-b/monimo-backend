package com.monimo.api.common.web

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import java.util.Base64

// 커서 = 마지막 줄 위치를 담은 JSON 을 Base64URL 로 감싼 문자열 (API 명세 §0). 화면은 속을 모르고 그대로 되돌려 보낸다
object CursorCodec {
    private val mapper = jacksonObjectMapper()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(position: Any): String = encoder.encodeToString(mapper.writeValueAsBytes(position))

    fun <T> decode(cursor: String, type: Class<T>): T =
        try {
            mapper.readValue(decoder.decode(cursor), type)
        } catch (e: IllegalArgumentException) {
            throw invalidCursor()
        } catch (e: JacksonException) {
            throw invalidCursor()
        }

    inline fun <reified T> decode(cursor: String): T = decode(cursor, T::class.java)

    // limit + 1 줄을 읽어 넘치면 다음 쪽이 있다고 보고, 이 쪽 마지막 줄 위치로 next_cursor 를 만든다
    fun <T> page(rows: List<T>, limit: Int, positionOf: (T) -> Any): ApiResponse<List<T>> {
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) encode(positionOf(items.last())) else null
        return ApiResponse.page(items, nextCursor, limit)
    }

    private fun invalidCursor() = ApiException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.")
}

package com.monimo.api.common.web

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode

// limit 파라미터 검사 (API 명세 §0: 기본 50, 최대 500). 스캐터처럼 기본값 · 상한이 다른 API 는 값을 넘긴다
object PageLimit {
    const val DEFAULT = 50
    const val MAX = 500

    fun of(limit: Int?, default: Int = DEFAULT, max: Int = MAX): Int {
        val value = limit ?: default
        if (value !in 1..max) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "limit 은 1 이상 $max 이하여야 합니다.")
        }
        return value
    }
}

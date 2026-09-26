package com.monimo.api.common.error

// 실패 응답 봉투 (API 명세 §0). { error: { code, message } }, data 와 같이 나가지 않는다
data class ErrorResponse(
    val error: ErrorBody,
) {
    data class ErrorBody(
        val code: String,
        val message: String,
    )

    companion object {
        fun of(errorCode: ErrorCode, message: String) = ErrorResponse(ErrorBody(errorCode.name, message))
    }
}

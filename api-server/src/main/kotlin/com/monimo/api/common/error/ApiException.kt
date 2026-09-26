package com.monimo.api.common.error

// 명세 에러 코드로 응답하고 싶을 때 던진다. GlobalExceptionHandler 가 에러 봉투로 바꾼다
class ApiException(
    val errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
) : RuntimeException(message)

package com.monimo.api.common.error

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// 컨트롤러에서 나온 예외를 명세 §0 에러 봉투로 바꾼다.
// 스프링 MVC 기본 예외(파라미터 누락 · 형식 오류 · 없는 주소 등)는 부모 클래스가 상태 코드를 정하고, 본문만 여기서 바꾼다.
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ErrorResponse> =
        respond(ex.errorCode, ex.message ?: ex.errorCode.defaultMessage)

    // ClickHouse · PostgreSQL 연결 실패 · 시간 초과
    @ExceptionHandler(DataAccessResourceFailureException::class, TransientDataAccessException::class)
    fun handleUpstreamUnavailable(ex: Exception): ResponseEntity<ErrorResponse> {
        log.warn("저장소 응답 없음", ex)
        return respond(ErrorCode.UPSTREAM_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE.defaultMessage)
    }

    // 예상 못한 예외는 내부 메시지를 숨기고 500 으로 응답한다
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리하지 못한 예외", ex)
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errorCode = when {
            statusCode.value() == 404 -> ErrorCode.NOT_FOUND
            statusCode.is4xxClientError -> ErrorCode.INVALID_REQUEST
            else -> ErrorCode.INTERNAL_ERROR
        }
        val message = when (ex) {
            is MissingServletRequestParameterException -> "필수 파라미터 '${ex.parameterName}' 이(가) 없습니다."
            is MethodArgumentTypeMismatchException -> "파라미터 '${ex.name}' 의 값 형식이 올바르지 않습니다."
            is TypeMismatchException -> "파라미터 '${ex.propertyName}' 의 값 형식이 올바르지 않습니다."
            else -> errorCode.defaultMessage
        }
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(errorCode, message))
    }

    private fun respond(errorCode: ErrorCode, message: String) =
        ResponseEntity.status(errorCode.status).body(ErrorResponse.of(errorCode, message))

    private companion object {
        val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}

package com.monimo.api.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.error.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

// 시큐리티 필터에서 막힌 요청도 명세 §0 에러 봉투로 응답한다. 컨트롤러 앞에서 끝나 GlobalExceptionHandler 가 못 잡기 때문
class ApiAuthErrorHandlers(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) =
        write(response, ErrorCode.UNAUTHENTICATED)

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) =
        write(response, ErrorCode.FORBIDDEN)

    private fun write(response: HttpServletResponse, errorCode: ErrorCode) {
        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.of(errorCode, errorCode.defaultMessage))
    }
}

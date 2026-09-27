package com.monimo.api.auth

import com.monimo.api.common.security.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

// refresh 토큰을 담는 httpOnly 쿠키. 자바스크립트가 못 읽어 XSS 로 새지 않는다.
// Path 를 /api/v1/auth 로 좁혀 다른 API 호출에는 붙지 않게 하고, SameSite=Strict 로 다른 사이트발 요청(CSRF)을 막는다
@Component
class RefreshCookie(private val properties: AuthProperties) {

    fun issue(token: String): ResponseCookie = base(token).maxAge(properties.refreshTtl).build()

    fun clear(): ResponseCookie = base("").maxAge(0).build()

    fun read(request: HttpServletRequest): String? = request.cookies?.firstOrNull { it.name == NAME }?.value

    private fun base(value: String) = ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(properties.cookieSecure)
        .sameSite("Strict")
        .path(PATH)

    companion object {
        const val NAME = "monimo_refresh"
        const val PATH = "/api/v1/auth"
    }
}

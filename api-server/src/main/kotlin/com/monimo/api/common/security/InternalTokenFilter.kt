package com.monimo.api.common.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

// 내부 문(/api/v1/internal/**)은 JWT 대신 X-Internal-Token 을 본다 (API 명세 §0-3). 맞으면 INTERNAL 권한을 준다
class InternalTokenFilter(private val expectedToken: String) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(PATH_PREFIX)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val presented = request.getHeader(HEADER)
        if (presented != null && matches(presented)) {
            val authentication = UsernamePasswordAuthenticationToken("internal", null, listOf(SimpleGrantedAuthority(SecurityConfig.INTERNAL_AUTHORITY)))
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }

    // 비어 있으면 어떤 값도 통과시키지 않는다. 길이가 달라도 시간이 같도록 상수 시간 비교
    private fun matches(presented: String): Boolean =
        expectedToken.isNotBlank() && MessageDigest.isEqual(expectedToken.toByteArray(), presented.toByteArray())

    companion object {
        const val HEADER = "X-Internal-Token"
        const val PATH_PREFIX = "/api/v1/internal/"
    }
}

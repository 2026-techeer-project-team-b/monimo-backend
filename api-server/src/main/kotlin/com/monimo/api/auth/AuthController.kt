package com.monimo.api.auth

import com.monimo.api.auth.dto.LoginRequest
import com.monimo.api.auth.dto.LoginResponse
import com.monimo.api.auth.dto.LogoutResponse
import com.monimo.api.auth.dto.TokenResponse
import com.monimo.api.auth.dto.UserResponse
import com.monimo.api.common.web.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 인증 4개 문 (API 명세 §0-2 공개 2 · VIEWER+ 2). refresh 토큰은 본문이 아니라 httpOnly 쿠키로 오간다
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val refreshCookie: RefreshCookie,
) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        val issued = authService.login(request.email, request.password)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(issued.refreshToken).toString())
            .body(ApiResponse.of(LoginResponse(issued.accessToken, issued.expiresIn, UserResponse.from(issued.user))))
    }

    @PostMapping("/refresh")
    fun refresh(request: HttpServletRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val issued = authService.refresh(refreshCookie.read(request))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(issued.refreshToken).toString())
            .body(ApiResponse.of(TokenResponse(issued.accessToken, issued.expiresIn)))
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<ApiResponse<LogoutResponse>> {
        authService.logout(refreshCookie.read(request))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
            .body(ApiResponse.of(LogoutResponse()))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserResponse> =
        ApiResponse.of(UserResponse.from(authService.me(UUID.fromString(jwt.subject))))
}

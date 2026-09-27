package com.monimo.api.common.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 인증 설정 (application.yml monimo.auth)
@ConfigurationProperties("monimo.auth")
data class AuthProperties(
    // access JWT 서명 키. 비우면 JwtConfig 가 기동 때 임시 키를 만든다
    val jwtSecret: String = "",
    val accessTtl: Duration = Duration.ofHours(1),
    val refreshTtl: Duration = Duration.ofDays(14),
    val cookieSecure: Boolean = true,
    // users 표가 비어 있을 때 만들 첫 ADMIN. 로컬 프로필에서만 채운다
    val bootstrapAdmin: BootstrapAdmin? = null,
) {
    data class BootstrapAdmin(
        val email: String,
        val password: String,
        val name: String? = null,
    )
}

// 내부 문 5개가 검사하는 공유 비밀값 (application.yml monimo.internal-token). 비우면 내부 문이 전부 닫힌다
@ConfigurationProperties("monimo")
data class InternalTokenProperties(
    val internalToken: String = "",
)

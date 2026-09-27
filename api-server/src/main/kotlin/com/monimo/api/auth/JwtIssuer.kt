package com.monimo.api.auth

import com.monimo.api.common.security.AuthProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant

// access JWT 발급. sub = user_uuid, role · email 클레임. 검사는 SecurityConfig 의 JwtDecoder 가 같은 키로 한다
@Component
class JwtIssuer(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
) {
    val expiresInSeconds: Long
        get() = properties.accessTtl.seconds

    fun issue(user: User, now: Instant): String {
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.userUuid.toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl))
            .claim(ROLE_CLAIM, user.role.name)
            .claim("email", user.email)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    companion object {
        const val ISSUER = "monimo"
        const val ROLE_CLAIM = "role"
    }
}

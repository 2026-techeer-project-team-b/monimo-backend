package com.monimo.api.common.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// access JWT 의 서명 키 · 발급기 · 검사기. HS256 대칭 키 하나를 발급과 검사가 같이 쓴다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class, InternalTokenProperties::class)
class JwtConfig {

    @Bean
    fun jwtSecretKey(properties: AuthProperties): SecretKey {
        val bytes = if (properties.jwtSecret.isBlank()) {
            log.warn("monimo.auth.jwt-secret 이 비어 있어 임시 서명 키를 만든다. 재시작하면 발급한 토큰이 전부 무효가 된다 (로컬 · 테스트 전용)")
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        } else {
            properties.jwtSecret.toByteArray()
        }
        // HS256 은 키가 256비트보다 짧으면 Nimbus 가 거절한다
        require(bytes.size >= 32) { "monimo.auth.jwt-secret 은 32바이트 이상이어야 한다 (지금 ${bytes.size}바이트)" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(jwtSecretKey: SecretKey): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(jwtSecretKey))

    @Bean
    fun jwtDecoder(jwtSecretKey: SecretKey): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build()

    private companion object {
        val log = LoggerFactory.getLogger(JwtConfig::class.java)
    }
}

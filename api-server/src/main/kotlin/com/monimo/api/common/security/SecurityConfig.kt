package com.monimo.api.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain

// API 명세 §0-2 권한 4종을 경로로 가른다. 공개 = login · refresh / 내부 = X-Internal-Token / 그 외 /api/v1/** = JWT (VIEWER+)
// ADMIN 은 각 컨트롤러가 @PreAuthorize("hasRole('ADMIN')") 로 건다. 에이전트(mTLS)는 수집기 몫이라 여기 없다
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import(JwtConfig::class)
class SecurityConfig {

    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        internalTokenProperties: InternalTokenProperties,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        val errors = ApiAuthErrorHandlers(objectMapper)
        http
            // 세션이 없고, 쿠키가 붙는 문은 refresh · logout 뿐이라 SameSite=Strict 쿠키로 막는다 (RefreshCookie)
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .requestMatchers("/api/v1/internal/**").hasAuthority(INTERNAL_AUTHORITY)
                    .requestMatchers("/api/v1/**").authenticated()
                    // 업무 API 는 전부 /api/v1 아래다. 나머지는 헬스체크 · actuator · springdoc(local) 이라 열어 둔다
                    .anyRequest().permitAll()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.decoder(jwtDecoder).jwtAuthenticationConverter(roleConverter()) }
                rs.authenticationEntryPoint(errors)
                rs.accessDeniedHandler(errors)
            }
            .exceptionHandling { it.authenticationEntryPoint(errors).accessDeniedHandler(errors) }
            // 필터는 @Component 로 두지 않는다. 두면 스프링 부트가 서블릿 필터로도 한 번 더 등록한다
            .addFilterBefore(InternalTokenFilter(internalTokenProperties.internalToken), BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    // JWT 의 role 클레임(ADMIN · VIEWER) → ROLE_ADMIN · ROLE_VIEWER. hasRole("ADMIN") 이 이 접두를 기대한다
    private fun roleConverter() = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
            listOfNotNull(jwt.getClaimAsString("role")?.let { SimpleGrantedAuthority("ROLE_$it") })
        }
    }

    companion object {
        const val INTERNAL_AUTHORITY = "INTERNAL"
    }
}

package com.monimo.api.auth

import com.monimo.api.common.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

// users 표가 비어 있고 monimo.auth.bootstrap-admin 이 있으면 첫 ADMIN 을 만든다. 회원가입 API 가 없어서 첫 계정은 이 길뿐이다
@Component
class BootstrapAdmin(
    private val properties: AuthProperties,
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val admin = properties.bootstrapAdmin ?: return
        if (users.count() > 0) return
        val now = Instant.now()
        users.save(User(UUID.randomUUID(), admin.email, passwordEncoder.encode(admin.password), admin.name, UserRole.ADMIN, now, now))
        log.info("첫 ADMIN 계정을 만들었다: {}", admin.email)
    }

    private companion object {
        val log = LoggerFactory.getLogger(BootstrapAdmin::class.java)
    }
}

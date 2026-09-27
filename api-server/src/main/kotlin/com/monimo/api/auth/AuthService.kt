package com.monimo.api.auth

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.security.AuthProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

// 로그인 · 재발급 · 로그아웃. access 는 JWT(무상태), refresh 는 난수 + PG 해시(상태) 라 로그아웃이 실제로 무효화한다
@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtIssuer: JwtIssuer,
    private val properties: AuthProperties,
) {
    class Issued(
        val accessToken: String,
        val expiresIn: Long,
        val refreshToken: String,
        val user: User,
    )

    @Transactional
    fun login(email: String, password: String): Issued {
        val user = users.findByEmail(email)
        // 이메일이 없어도 비밀번호 비교를 건너뛰지 않는다. 응답 시간으로 계정 존재 여부가 새지 않게
        val matched = passwordEncoder.matches(password, user?.passwordHash ?: DUMMY_HASH)
        if (user == null || !matched) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "이메일 또는 비밀번호가 맞지 않습니다.")
        }
        return issue(user)
    }

    // 회전: 쓴 refresh 는 지우고 새로 준다. 훔친 토큰과 정상 사용자가 번갈아 쓰면 한쪽이 곧 401 을 받아 드러난다.
    // 401 로 끝나도 만료 줄 정리는 남겨야 해서 ApiException 은 롤백시키지 않는다
    @Transactional(noRollbackFor = [ApiException::class])
    fun refresh(rawRefreshToken: String?): Issued {
        val raw = rawRefreshToken ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "재발급 토큰이 없습니다.")
        val now = Instant.now()
        refreshTokens.deleteByExpiresAtBefore(now)
        val row = refreshTokens.findByTokenHash(sha256Hex(raw))
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "재발급 토큰이 만료됐거나 무효입니다.")
        refreshTokens.delete(row)
        val user = users.findById(row.userId).orElseThrow { ApiException(ErrorCode.UNAUTHENTICATED) }
        return issue(user, now)
    }

    // 쿠키가 없거나 이미 지워진 토큰이어도 실패로 보지 않는다. 로그아웃은 몇 번 눌러도 결과가 같아야 한다
    @Transactional
    fun logout(rawRefreshToken: String?) {
        rawRefreshToken?.let { raw -> refreshTokens.findByTokenHash(sha256Hex(raw))?.let(refreshTokens::delete) }
    }

    @Transactional(readOnly = true)
    fun me(userUuid: UUID): User =
        users.findByUserUuid(userUuid) ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "탈퇴했거나 없는 사용자입니다.")

    private fun issue(user: User, now: Instant = Instant.now()): Issued {
        val raw = ByteArray(REFRESH_BYTES).also { random.nextBytes(it) }.let(base64Url::encodeToString)
        refreshTokens.save(RefreshToken(sha256Hex(raw), user.id!!, now.plus(properties.refreshTtl), now))
        return Issued(jwtIssuer.issue(user, now), jwtIssuer.expiresInSeconds, raw, user)
    }

    private companion object {
        const val REFRESH_BYTES = 32
        val random = SecureRandom()
        val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        // 존재하지 않는 계정에도 같은 비용의 bcrypt 비교를 하기 위한 자리 채움 해시 (아무 문자열의 해시)
        const val DUMMY_HASH = "\$2y\$10\$Y8rs2wZOWhEKGuXb9Wo/RubvLi0UiLiFN7XkjRkKP85GTNifefOPW"
    }
}

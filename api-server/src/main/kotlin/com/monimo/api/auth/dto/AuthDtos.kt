package com.monimo.api.auth.dto

import com.monimo.api.auth.User
import com.monimo.api.auth.UserRole
import java.time.Instant
import java.util.UUID

data class LoginRequest(
    val email: String,
    val password: String,
)

data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val user: UserResponse,
)

data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long,
)

data class LogoutResponse(
    val result: String = "LOGGED_OUT",
)

data class UserResponse(
    val userUuid: UUID,
    val email: String,
    val name: String?,
    val role: UserRole,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) = UserResponse(user.userUuid, user.email, user.name, user.role, user.createdAt)
    }
}

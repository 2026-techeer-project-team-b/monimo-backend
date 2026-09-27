package com.monimo.api.config.dto

import com.monimo.api.config.Application
import java.time.Instant
import java.util.UUID

data class CreateApplicationRequest(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
)

// 둘 다 선택. 안 보낸 필드는 그대로, 빈 문자열은 지운다(null)
data class UpdateApplicationRequest(
    val displayName: String? = null,
    val description: String? = null,
)

data class ApplicationResponse(
    val applicationUuid: UUID,
    val name: String,
    val displayName: String?,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(app: Application) =
            ApplicationResponse(app.applicationUuid, app.name, app.displayName, app.description, app.createdAt, app.updatedAt)
    }
}

data class ApplicationDetailResponse(
    val applicationUuid: UUID,
    val name: String,
    val displayName: String?,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val agentCount: Int,
) {
    companion object {
        fun from(app: Application, agentCount: Int) = ApplicationDetailResponse(
            app.applicationUuid, app.name, app.displayName, app.description, app.createdAt, app.updatedAt, agentCount,
        )
    }
}

data class ApplicationDeletedResponse(
    val applicationUuid: UUID,
    val result: String = "DELETED",
)

package com.monimo.api.config.dto

import com.monimo.api.auth.User
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationConfig
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// 둘 다 필수. 없으면 400 으로 돌려주려고 nullable 로 받는다 (코틀린 non-null 로 받으면 역직렬화에서 500 이 난다)
data class UpdateApplicationConfigRequest(
    val samplingRate: BigDecimal? = null,
    val expectedVersion: Int? = null,
)

data class ApplicationConfigResponse(
    val applicationConfigUuid: UUID,
    val applicationUuid: UUID,
    val serviceName: String,
    val samplingRate: BigDecimal,
    val version: Int,
    val updatedBy: UpdatedByResponse?,
    val updatedAt: Instant,
) {
    companion object {
        // updatedBy 는 처음 만든 뒤 아무도 안 고쳤으면 null
        fun from(app: Application, config: ApplicationConfig, editor: User?) = ApplicationConfigResponse(
            config.applicationConfigUuid,
            app.applicationUuid,
            app.name,
            config.samplingRate,
            config.version,
            editor?.let { UpdatedByResponse(it.userUuid, it.name) },
            config.updatedAt,
        )
    }
}

data class UpdatedByResponse(
    val userUuid: UUID,
    val name: String?,
)

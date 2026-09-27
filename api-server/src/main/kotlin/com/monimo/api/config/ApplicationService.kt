package com.monimo.api.config

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.config.dto.ApplicationDetailResponse
import com.monimo.api.config.dto.ApplicationResponse
import com.monimo.api.config.dto.CreateApplicationRequest
import com.monimo.api.config.dto.UpdateApplicationRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

// 서비스 등록 · 목록 · 상세 · 수정 · 제외. 제외는 deleted_at 만 채우고 줄은 남긴다
@Service
class ApplicationService(
    private val applications: ApplicationRepository,
    private val configs: ApplicationConfigRepository,
) {
    // 목록 커서. name 이 UK 라 위치로 충분하다
    data class Cursor(val name: String)

    @Transactional
    fun create(request: CreateApplicationRequest): Application {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > NAME_MAX) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "name 은 1자 이상 ${NAME_MAX}자 이하여야 합니다.")
        }
        applications.findByName(name)?.let { existing ->
            val reason = if (existing.deletedAt != null) "감시 대상에서 제외된 서비스 이름입니다." else ErrorCode.APPLICATION_NAME_TAKEN.defaultMessage
            throw ApiException(ErrorCode.APPLICATION_NAME_TAKEN, reason)
        }
        val now = Instant.now()
        val app = try {
            // 위 조회와 저장 사이에 같은 이름이 들어오면 UK 위반으로 온다. 500 이 아니라 409 로
            applications.saveAndFlush(
                Application(UUID.randomUUID(), name, displayNameOf(request.displayName), blankToNull(request.description), now, now),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ApiException(ErrorCode.APPLICATION_NAME_TAKEN)
        }
        configs.save(
            ApplicationConfig(
                UUID.randomUUID(), app.id!!, ApplicationConfig.DEFAULT_SAMPLING_RATE, ApplicationConfig.FIRST_VERSION, null, now, now,
            ),
        )
        return app
    }

    @Transactional(readOnly = true)
    fun list(cursor: String?, limit: Int): ApiResponse<List<ApplicationResponse>> {
        val after = cursor?.let { CursorCodec.decode<Cursor>(it).name }
        val rows = applications.findActiveAfter(after, PageRequest.of(0, limit + 1))
        return CursorCodec.page(rows.map(ApplicationResponse::from), limit) { Cursor(it.name) }
    }

    @Transactional(readOnly = true)
    fun get(applicationUuid: UUID): ApplicationDetailResponse {
        val app = find(applicationUuid)
        // agents 표(수집 파트)가 아직 없어 0 으로 준다
        return ApplicationDetailResponse.from(app, agentCount = 0)
    }

    @Transactional
    fun update(applicationUuid: UUID, request: UpdateApplicationRequest): Application {
        if (request.displayName == null && request.description == null) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "display_name 또는 description 중 하나는 있어야 합니다.")
        }
        val app = find(applicationUuid)
        request.displayName?.let { app.displayName = displayNameOf(it) }
        request.description?.let { app.description = blankToNull(it) }
        app.updatedAt = Instant.now()
        return app
    }

    @Transactional
    fun delete(applicationUuid: UUID): Application {
        val app = find(applicationUuid)
        val now = Instant.now()
        app.deletedAt = now
        app.updatedAt = now
        return app
    }

    // 제외된 서비스는 없는 것과 같이 404
    private fun find(applicationUuid: UUID): Application =
        applications.findByApplicationUuidAndDeletedAtIsNull(applicationUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "서비스를 찾을 수 없습니다.")

    private fun displayNameOf(value: String?): String? {
        val trimmed = blankToNull(value) ?: return null
        if (trimmed.length > DISPLAY_NAME_MAX) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "display_name 은 ${DISPLAY_NAME_MAX}자 이하여야 합니다.")
        }
        return trimmed
    }

    private fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val NAME_MAX = 100
        const val DISPLAY_NAME_MAX = 200
    }
}

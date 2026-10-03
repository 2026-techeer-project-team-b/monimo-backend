package com.monimo.api.config

import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.config.dto.ApplicationConfigResponse
import com.monimo.api.config.dto.UpdateApplicationConfigRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

// 샘플링 설정 조회 · 수정. 수집기가 30초 캐시로 읽는 값이라 재배포 없이 바꾼다 (ADR #37)
@Service
class ApplicationConfigService(
    private val applications: ApplicationRepository,
    private val configs: ApplicationConfigRepository,
    private val users: UserRepository,
) {

    @Transactional(readOnly = true)
    fun get(applicationUuid: UUID): ApplicationConfigResponse {
        val app = find(applicationUuid)
        return response(app, config(app))
    }

    @Transactional
    fun update(applicationUuid: UUID, editorUuid: UUID, request: UpdateApplicationConfigRequest): ApplicationConfigResponse {
        val samplingRate = samplingRateOf(request.samplingRate)
        val expectedVersion = request.expectedVersion
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "expected_version 은 필수입니다.")
        val app = find(applicationUuid)
        val editor = users.findByUserUuid(editorUuid)
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "탈퇴했거나 없는 사용자입니다.")

        // 설정 줄은 서비스를 등록할 때 같이 생기므로, 0 줄은 version 이 어긋난 경우뿐이다
        if (configs.updateIfVersionMatches(app.id!!, expectedVersion, samplingRate, editor.id!!, Instant.now()) == 0) {
            throw ApiException(ErrorCode.CONFIG_VERSION_CONFLICT)
        }
        return response(app, config(app))
    }

    // 제외된 서비스는 없는 것과 같이 404
    private fun find(applicationUuid: UUID): Application =
        applications.findByApplicationUuidAndDeletedAtIsNull(applicationUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "서비스를 찾을 수 없습니다.")

    private fun config(app: Application): ApplicationConfig =
        configs.findByApplicationId(app.id!!)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "설정을 찾을 수 없습니다.")

    private fun response(app: Application, config: ApplicationConfig): ApplicationConfigResponse =
        ApplicationConfigResponse.from(app, config, editorOf(config))

    private fun editorOf(config: ApplicationConfig): User? =
        config.updatedBy?.let { users.findById(it).orElse(null) }

    // 표가 NUMERIC(5,4) 라 소수 5째 자리부터는 어차피 버려진다. 보낸 값과 돌려주는 값이 다르지 않도록 여기서 맞춘다
    private fun samplingRateOf(value: BigDecimal?): BigDecimal {
        if (value == null) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "sampling_rate 는 필수입니다.")
        }
        if (value < BigDecimal.ZERO || value > BigDecimal.ONE) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "sampling_rate 는 0 이상 1 이하여야 합니다.")
        }
        return value.setScale(SAMPLING_RATE_SCALE, RoundingMode.HALF_UP)
    }

    private companion object {
        const val SAMPLING_RATE_SCALE = 4
    }
}

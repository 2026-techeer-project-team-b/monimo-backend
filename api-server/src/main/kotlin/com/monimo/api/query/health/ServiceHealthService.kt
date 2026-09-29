package com.monimo.api.query.health

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.health.dto.ServiceHealthResponse
import org.springframework.stereotype.Service

// 요청이 0건인 버킷은 행이 없다. 0 으로 채우면 아직 적재가 안 끝난 1분과 구분이 안 돼 탐지가 정상으로 오판한다
@Service
class ServiceHealthService(
    private val serviceHealthRepository: ServiceHealthRepository,
) {

    fun list(serviceName: String?, range: TimeRange, step: Int): List<ServiceHealthResponse> {
        // 재료가 1분 표뿐이라 1분보다 잘게 나눌 수 없다
        if (step < 60 || step % 60 != 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "step 은 60 이상 60 의 배수여야 합니다.")
        }
        return serviceHealthRepository.find(serviceName, range, step)
    }
}

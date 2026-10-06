package com.monimo.api.query.health

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.health.dto.ServiceHealthResponse
import com.monimo.api.query.support.Rollup
import org.springframework.stereotype.Service

// 요청이 0건인 버킷은 행이 없다. 0 으로 채우면 아직 적재가 안 끝난 1분과 구분이 안 돼 탐지가 정상으로 오판한다
@Service
class ServiceHealthService(
    private val serviceHealthRepository: ServiceHealthRepository,
) {

    fun list(serviceName: String?, range: TimeRange, step: Int): List<ServiceHealthResponse> {
        return serviceHealthRepository.find(serviceName, range, Rollup.requireMinuteStep(step))
    }
}

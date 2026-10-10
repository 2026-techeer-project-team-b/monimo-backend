package com.monimo.api.query.support

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.config.ApplicationRepository
import org.springframework.stereotype.Component

// service_name 이 등록된 감시 대상(PG applications, 제외되지 않은 것)인지 확인한다. 조회 API 가 404 를 낼 때 쓴다
@Component
class MonitoredServices(
    private val applications: ApplicationRepository,
) {

    fun require(serviceName: String) {
        val application = applications.findByName(serviceName)
        if (application == null || application.deletedAt != null) {
            throw ApiException(ErrorCode.NOT_FOUND, "서비스 '$serviceName' 을(를) 찾을 수 없습니다.")
        }
    }
}

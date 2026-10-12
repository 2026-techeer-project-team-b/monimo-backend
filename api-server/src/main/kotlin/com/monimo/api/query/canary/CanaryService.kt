package com.monimo.api.query.canary

import com.monimo.api.query.canary.dto.CanaryFreshnessResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

// 가장 최근 카나리가 몇 초 전인지와, 기준 안인지를 낸다
@Service
@EnableConfigurationProperties(CanaryProperties::class)
class CanaryService(
    private val canaryRepository: CanaryRepository,
    private val properties: CanaryProperties,
) {

    fun freshness(serviceName: String?): CanaryFreshnessResponse {
        val now = Instant.now()
        val latest = canaryRepository.findLatest(serviceName, now.minus(LOOKBACK))
            // 카나리가 없는 것은 조회 실패가 아니라 파이프라인이 끊긴 것이다. 404 가 아니라 fresh = false 로 답한다
            ?: return CanaryFreshnessResponse(serviceName, null, null, properties.thresholdSec, fresh = false)

        // 쇼핑몰 시계가 조금 앞서면 음수가 나올 수 있어 0 으로 막는다
        val ageSec = Duration.between(latest.at, now).seconds.coerceAtLeast(0)
        return CanaryFreshnessResponse(
            serviceName = serviceName ?: latest.serviceName,
            lastSignalAt = latest.at,
            ageSec = ageSec,
            thresholdSec = properties.thresholdSec,
            fresh = ageSec <= properties.thresholdSec,
        )
    }

    private companion object {
        // 원본 표 전체를 훑지 않게 최근 하루만 본다. 그보다 오래 끊겼으면 "없음" 으로 답한다
        val LOOKBACK: Duration = Duration.ofHours(24)
    }
}

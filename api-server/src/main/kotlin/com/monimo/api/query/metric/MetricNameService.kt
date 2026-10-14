package com.monimo.api.query.metric

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.MetricNameResponse
import com.monimo.api.query.support.MonitoredServices
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

// from · to 가 빠지면 최근 1시간. 들어온 지표가 없으면 빈 목록
@Service
class MetricNameService(
    private val metricNameRepository: MetricNameRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun list(serviceName: String, from: Instant?, to: Instant?): List<MetricNameResponse> {
        val end = to ?: Instant.now()
        val range = TimeRange.of(from ?: end.minus(DEFAULT_WINDOW), end)
        monitoredServices.require(serviceName)
        return metricNameRepository.find(serviceName, range)
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)
    }
}

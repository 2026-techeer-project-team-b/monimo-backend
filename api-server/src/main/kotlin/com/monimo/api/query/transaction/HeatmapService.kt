package com.monimo.api.query.transaction

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.support.MonitoredServices
import com.monimo.api.query.support.Rollup
import com.monimo.api.query.transaction.dto.HeatmapResponse
import org.springframework.stereotype.Service

// 스캐터는 점이 많으면 접지만 히트맵은 요약 표라 요청을 빠짐없이 센다
@Service
class HeatmapService(
    private val heatmapRepository: HeatmapRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun get(serviceName: String, range: TimeRange, step: Int): HeatmapResponse {
        Rollup.requireMinuteStep(step)
        if (range.duration.seconds / step > MAX_COLUMNS) {
            throw ApiException(ErrorCode.TIME_RANGE_TOO_WIDE, "가로 칸이 ${MAX_COLUMNS}개를 넘습니다. step 을 키우거나 범위를 줄이세요.")
        }
        monitoredServices.require(serviceName)
        return HeatmapResponse(BUCKET_WIDTH_MS, step, heatmapRepository.find(serviceName, range, step))
    }

    companion object {
        // mv_heatmap_1m 이 응답시간을 나누는 폭. MV 를 바꾸면 같이 고친다
        const val BUCKET_WIDTH_MS = 50

        // 시간 축 칸 수 상한
        const val MAX_COLUMNS = 2000
    }
}

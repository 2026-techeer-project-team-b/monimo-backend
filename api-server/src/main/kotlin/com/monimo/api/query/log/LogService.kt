package com.monimo.api.query.log

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.log.dto.LogCursor
import com.monimo.api.query.log.dto.LogResponse
import com.monimo.api.query.log.dto.LogSearch
import com.monimo.api.query.support.NanoTime
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class LogService(
    private val logRepository: LogRepository,
) {

    fun list(search: LogSearch, range: TimeRange, cursor: String?, limit: Int): ApiResponse<List<LogResponse>> {
        // 지워진 구간을 빈 목록으로 답하면 "로그가 없었다" 로 읽힌다. 지워졌다고 알린다
        if (range.from.isBefore(Instant.now().minus(RETENTION))) {
            throw ApiException(ErrorCode.SIGNAL_EXPIRED, "보관 기간(${RETENTION.toDays()}일)이 지난 범위의 로그는 지워졌습니다.")
        }
        val position = cursor?.let { CursorCodec.decode<LogCursor>(it) }
        val rows = logRepository.find(search, range, position, limit)
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) CursorCodec.encode(nextPosition(items, position)) else null
        return ApiResponse.page(items.map { it.toResponse() }, nextCursor, limit)
    }

    // 이 쪽 끝이 걸친 ms 에서 지금까지 준 줄 수를 센다. 쪽 전체가 커서와 같은 ms 였으면 앞에서 건너뛴 수를 이어 더한다
    private fun nextPosition(items: List<LogRow>, previous: LogCursor?): LogCursor {
        val lastTs = items.last().tsMs
        val sameTs = items.count { it.tsMs == lastTs }
        val carried = if (previous != null && previous.ts == lastTs) previous.skip else 0
        return LogCursor(lastTs, sameTs + carried)
    }

    private fun LogRow.toResponse() = LogResponse(
        ts = NanoTime.formatMillis(tsMs),
        serviceName = serviceName,
        agentKey = agentKey,
        level = level,
        logger = logger,
        thread = thread,
        message = message,
        traceId = traceId,
        spanId = spanId,
        attributes = attributes,
    )

    companion object {
        // logs 표 TTL (db/clickhouse). 표를 바꾸면 같이 고친다
        val RETENTION: Duration = Duration.ofDays(97)

        // 적재가 OTel severity_number 기준으로 고정한 여섯 이름 (ADR #59)
        val LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
    }
}

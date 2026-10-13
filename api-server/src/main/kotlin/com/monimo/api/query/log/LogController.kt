package com.monimo.api.query.log

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.log.dto.LogResponse
import com.monimo.api.query.log.dto.LogSearch
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 로그 검색 (API 명세 #35 · FN-61, VIEWER+). 시각 역순. service_name 없이 trace_id 만으로도 부른다(트레이스 상세의 연결 로그)
@RestController
@RequestMapping("/api/v1/logs")
class LogController(
    private val logService: LogService,
) {

    @GetMapping
    fun list(
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) logger: String?,
        @RequestParam("trace_id", required = false) traceId: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<LogResponse>> =
        logService.list(
            LogSearch(
                serviceName = serviceName.orNull(),
                agentKey = agentKey.orNull(),
                levels = parseLevels(level),
                loggerPrefix = logger.orNull(),
                traceId = traceId.orNull(),
                text = q.orNull(),
            ),
            TimeRange.of(from, to),
            cursor,
            PageLimit.of(limit),
        )

    // "ERROR,WARN" 처럼 쉼표로 여러 개. 대소문자는 가리지 않는다
    private fun parseLevels(level: String?): List<String> {
        val levels = level.orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val unknown = levels - LogService.LEVELS
        if (unknown.isNotEmpty()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "level 은 ${LogService.LEVELS.joinToString(" · ")} 중에서 고릅니다.")
        }
        return levels.distinct()
    }

    // 화면이 빈 입력칸을 빈 글자로 보내면 조건이 없는 것으로 본다
    private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }
}

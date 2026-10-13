package com.monimo.api.query.log.dto

// 로그 한 줄 (API 명세 #35). 요청과 이어지지 않은 로그(배치 등)는 trace_id · span_id 가 빈 글자다
data class LogResponse(
    val ts: String,
    val serviceName: String,
    val agentKey: String,
    val level: String,
    val logger: String,
    val thread: String,
    val message: String,
    val traceId: String,
    val spanId: String,
    val attributes: Map<String, String>,
)

// 검색 조건. levels 는 그중 하나, loggerPrefix 는 앞부분 일치, text 는 본문에 들어 있으면(대소문자 무관)
data class LogSearch(
    val serviceName: String?,
    val agentKey: String?,
    val levels: List<String>,
    val loggerPrefix: String?,
    val traceId: String?,
    val text: String?,
)

// 로그는 같은 ms 에 여러 줄이 찍히고 고유 번호가 없다. 그래서 커서는 (마지막 줄 시각, 그 시각에서 이미 준 줄 수)다
data class LogCursor(
    val ts: Long,
    val skip: Int,
)

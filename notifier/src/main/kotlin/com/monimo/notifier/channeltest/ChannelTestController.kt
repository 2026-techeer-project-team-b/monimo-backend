package com.monimo.notifier.channeltest

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.monimo.notifier.channel.ChannelType
import com.monimo.notifier.channel.NotificationSender
import com.monimo.notifier.channel.OutboundMessage
import com.monimo.notifier.channel.SendResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

data class ChannelTestRequest(
    val type: String?,
    val config: Map<String, Any?>?,
)

enum class TestResult { SUCCESS, FAILED }

data class ChannelTestResult(val result: TestResult, val response: String)

// 응답 봉투는 api-server 와 같은 모양(data · error, snake_case)
data class DataEnvelope<T>(val data: T)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ErrorBody(val code: String, val message: String)

data class ErrorEnvelope(val error: ErrorBody)

// 채널 시험 발송 (API 명세 #17). api-server 만 X-Internal-Token 으로 부른다.
// 받은 config 로 어댑터를 한 번 부르고 끝이다 — outbox · 재시도 · 서킷을 거치지 않고, 사건이 없어 이력(notification_history)에도 남기지 않는다
@RestController
@RequestMapping("/internal/channels")
class ChannelTestController(
    senders: List<NotificationSender>,
    @Value("\${monimo.internal-token:}") private val internalToken: String,
) {
    private val senderByType = senders.associateBy { it.type }

    @PostMapping("/test")
    fun test(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestBody request: ChannelTestRequest,
    ): ResponseEntity<Any> {
        if (!tokenMatches(token)) return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "내부 토큰이 없거나 올바르지 않습니다.")
        val type = ChannelType.entries.firstOrNull { it.name == request.type }
            ?: return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "type 은 ${ChannelType.entries} 중 하나여야 합니다.")
        val config = request.config ?: return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "config 가 필요합니다.")
        // 4종 안이지만 어댑터가 아직 없는 유형은 "보내 봤더니 안 됐다"로 돌려준다 (발송 워커와 같은 판단)
        val sender = senderByType[type] ?: return ok(TestResult.FAILED, "$type 어댑터 미구현")
        sender.configError(config)?.let { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", it) }

        val result = sender.send(OutboundMessage(mapOf("transition" to "TEST")), config)
        log.info("채널 시험 발송 type={} 결과={}", type, result.javaClass.simpleName)
        return when (result) {
            is SendResult.Accepted -> ok(TestResult.SUCCESS, result.response)
            is SendResult.Permanent -> ok(TestResult.FAILED, result.reason)
            // 공급자가 429 · 5xx 로 답했으면 닿긴 한 것이라 FAILED. 연결조차 안 됐으면 503
            is SendResult.Retryable ->
                if (result.responded) ok(TestResult.FAILED, result.reason) else error(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", result.reason)
            is SendResult.Unknown -> error(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", result.reason)
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<Any> = error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.")

    // 비어 있으면 어떤 값도 통과시키지 않는다. 상수 시간 비교 (api-server InternalTokenFilter 와 같은 규칙)
    private fun tokenMatches(presented: String?): Boolean =
        presented != null && internalToken.isNotBlank() && MessageDigest.isEqual(internalToken.toByteArray(), presented.toByteArray())

    private fun ok(result: TestResult, response: String): ResponseEntity<Any> =
        ResponseEntity.ok(DataEnvelope(ChannelTestResult(result, response)))

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(ErrorEnvelope(ErrorBody(code, message)))

    private companion object {
        const val TOKEN_HEADER = "X-Internal-Token"
        val log = LoggerFactory.getLogger(ChannelTestController::class.java)
    }
}

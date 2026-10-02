package com.monimo.notifier.channel

import java.time.Duration

enum class ChannelType { SLACK, EMAIL, WEBHOOK, PAGERDUTY }

// 채널 호출 한 번의 결과. 재시도 여부는 여기서 정하지 않는다 — 분류만 하고 결정은 RetryPolicy 한 곳이 한다
sealed interface SendResult {
    // 공급자가 접수했다 (사람이 읽었다는 뜻은 아니다)
    data class Accepted(val response: String) : SendResult
    // 다시 보내면 될 수도 있다: 429 · 5xx · 연결 실패(요청이 나가기 전).
    // responded = 공급자가 응답은 했다(429 · 5xx). 시험 발송이 "거절"(200 FAILED)과 "닿지 않음"(503)을 가르는 데 쓴다
    data class Retryable(val reason: String, val retryAfter: Duration? = null, val responded: Boolean = false) : SendResult
    // 고치기 전에는 몇 번을 보내도 안 된다: 잘못된 주소 · 권한 · 형식
    data class Permanent(val reason: String) : SendResult
    // 요청은 나갔는데 결과를 모른다 (응답 시간 초과). 공급자가 이미 접수했을 수 있다
    data class Unknown(val reason: String) : SendResult
}

// 채널 전략. 채널별 요청 모양 · 오류 분류만 맡는다. 큐 · 재시도 · 서킷은 공통 워커가 맡으므로 여기서 복제하지 않는다
interface NotificationSender {
    val type: ChannelType
    fun send(message: OutboundMessage, config: Map<String, Any?>): SendResult

    // 보내기 전에 config 모양만 본다. 틀리면 이유, 맞으면 null. 시험 발송이 400 으로 돌려주는 데 쓴다
    fun configError(config: Map<String, Any?>): String? = null
}

// 발화 당시 스냅샷(outbox.payload)에서 만든 보낼 내용
data class OutboundMessage(val payload: Map<String, Any?>) {
    val transition: String get() = payload["transition"].toString()

    fun summary(): String {
        val p = payload
        return when (transition) {
            "TEST" -> "[TEST] monimo 시험 발송입니다. 이 메시지가 보이면 채널 설정이 맞습니다."
            "RESOLVED" -> "[RESOLVED] ${p["rule_name"]} — ${p["service_name"]} 복구 (발화 ${p["fired_at"]}, 복구 ${p["resolved_at"]})"
            else -> "[FIRING][${p["severity"]}] ${p["rule_name"]} — ${p["service_name"]} ${p["metric_kind"]} " +
                "${p["observed_value"]} ${p["operator"]} ${p["threshold"]} (발화 ${p["fired_at"]})"
        }
    }
}

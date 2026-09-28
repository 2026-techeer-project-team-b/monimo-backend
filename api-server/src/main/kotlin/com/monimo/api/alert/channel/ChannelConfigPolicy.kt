package com.monimo.api.alert.channel

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode

// 채널 유형별 config 규칙. 검사 · 비밀값 가리기 · 수정 때 가린 값 되돌리기를 한 곳에 둔다
// 키 이름은 알림 서비스 어댑터가 읽는 이름과 같다 (SLACK webhook_url = SlackWebhookSender)
object ChannelConfigPolicy {
    const val MASK = "****"

    // mask 가 있으면 비밀값이다
    private class Field(val required: Boolean, val mask: ((String) -> String)? = null, val check: (Any?) -> String?)

    private val fields: Map<ChannelType, Map<String, Field>> = mapOf(
        ChannelType.SLACK to mapOf(
            "webhook_url" to Field(required = true, mask = ::maskLastSegment) { httpsUrl(it, prefix = "https://hooks.slack.com/") },
            "channel" to Field(required = false) { text(it, max = 80) },
        ),
        ChannelType.WEBHOOK to mapOf(
            "url" to Field(required = true, mask = ::maskAfterHost) { httpsUrl(it, prefix = "https://") },
        ),
        ChannelType.EMAIL to mapOf(
            "to" to Field(required = true) { emails(it) },
        ),
        ChannelType.PAGERDUTY to mapOf(
            "routing_key" to Field(required = true, mask = ::maskAllButTail) { text(it, max = 64) },
        ),
    )

    // 모르는 키 · 빠진 필수 키 · 모양이 틀린 값은 400. null 인 선택 키는 저장하지 않는다
    fun validate(type: ChannelType, config: Map<String, Any?>?): Map<String, Any?> {
        if (config == null) throw invalid("config 가 필요합니다.")
        val spec = fields.getValue(type)
        (config.keys - spec.keys).firstOrNull()?.let { throw invalid("$type 채널에 없는 config 키입니다: $it") }
        spec.forEach { (key, field) ->
            val value = config[key]
            if (value == null) {
                if (field.required) throw invalid("config.$key 가 필요합니다.")
                return@forEach
            }
            field.check(value)?.let { throw invalid("config.$key $it") }
        }
        return config.filterValues { it != null }
    }

    // 응답용. 비밀값은 원래 값을 짐작할 수 없게 가린다
    fun mask(type: ChannelType, config: Map<String, Any?>): Map<String, Any?> {
        val spec = fields.getValue(type)
        return config.mapValues { (key, value) ->
            val mask = spec[key]?.mask
            if (mask != null && value is String) mask(value) else value
        }
    }

    // 수정 요청의 비밀값이 지금 응답으로 나가는 가린 값과 같으면 "안 바꿨다"로 보고 저장된 값을 쓴다. 유형이 바뀌면 되돌리지 않는다
    fun restoreSecrets(type: ChannelType, stored: Map<String, Any?>, incoming: Map<String, Any?>?): Map<String, Any?>? {
        if (incoming == null) return null
        val spec = fields.getValue(type)
        return incoming.mapValues { (key, value) ->
            val saved = stored[key]
            val mask = spec[key]?.mask
            if (mask != null && saved is String && value == mask(saved)) saved else value
        }
    }

    // Slack: .../T000/B000/**** (마지막 경로가 비밀)
    private fun maskLastSegment(value: String): String = value.substringBeforeLast('/') + "/" + MASK

    // 일반 웹훅: 경로 · 쿼리 어디에 토큰이 있을지 모르니 호스트 뒤를 전부 가린다
    private fun maskAfterHost(value: String): String {
        val hostStart = value.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val hostEnd = value.indexOf('/', hostStart).let { if (it < 0) value.length else it }
        return value.substring(0, hostEnd) + "/" + MASK
    }

    // 키: 끝 4자만 남긴다. 짧으면 전부 가린다
    private fun maskAllButTail(value: String): String = if (value.length > 8) MASK + value.takeLast(4) else MASK

    private fun text(value: Any?, max: Int): String? =
        if (value !is String || value.isBlank() || value.length > max) "는 1자 이상 ${max}자 이하 문자열이어야 합니다." else null

    private fun httpsUrl(value: Any?, prefix: String): String? =
        if (value !is String || !value.startsWith(prefix) || value.length <= prefix.length || value.length > 500 || value.any { it.isWhitespace() }) {
            "는 ${prefix} 로 시작하는 500자 이하 주소여야 합니다."
        } else {
            null
        }

    private fun emails(value: Any?): String? {
        val list = value as? List<*> ?: return "는 이메일 주소 배열이어야 합니다."
        if (list.size !in 1..20) return "는 1개 이상 20개 이하여야 합니다."
        return if (list.all { it is String && it.length <= 254 && EMAIL.matches(it) }) null else "에 올바르지 않은 이메일 주소가 있습니다."
    }

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    private fun invalid(message: String) = ApiException(ErrorCode.INVALID_REQUEST, message)
}

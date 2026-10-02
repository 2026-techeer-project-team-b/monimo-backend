package com.monimo.api.alert.channel.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.channel.ChannelType
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 알림 서비스 호출 설정 (application.yml monimo.notifier)
@ConfigurationProperties("monimo.notifier")
data class NotifierClientProperties(
    val baseUrl: String = "http://localhost:8084",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    // 알림 서비스가 채널을 부르는 시간(연결 2초 + 응답 5초)보다 길어야 "채널이 느림"과 "알림 서비스가 죽음"이 갈린다
    val requestTimeout: Duration = Duration.ofSeconds(10),
    // 같은 채널 시험 발송 사이 최소 간격. 외부로 실제 메시지가 나가는 문이라 연타를 막는다
    val testCooldown: Duration = Duration.ofSeconds(10),
)

sealed interface ChannelTestOutcome {
    // 알림 서비스가 채널을 불러 봤다. result = SUCCESS · FAILED
    data class Tested(val result: String, val response: String) : ChannelTestOutcome
    // 알림 서비스는 살아 있는데 채널 서버에 닿지 않았다 (연결 실패 · 시간 초과)
    data class ChannelUnreachable(val reason: String) : ChannelTestOutcome
    // 알림 서비스 자체가 응답하지 않거나 이상한 답을 했다
    data class NotifierUnavailable(val reason: String) : ChannelTestOutcome
}

fun interface ChannelTestClient {
    fun test(type: ChannelType, config: Map<String, Any?>): ChannelTestOutcome
}

// 알림 서비스 내부 문 POST /internal/channels/test (API 명세 #17). 외부로 실제 메시지가 나가므로 스스로 재시도하지 않는다
class HttpChannelTestClient(
    private val props: NotifierClientProperties,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : ChannelTestClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun test(type: ChannelType, config: Map<String, Any?>): ChannelTestOutcome {
        val body = objectMapper.writeValueAsString(mapOf("type" to type.name, "config" to config))
        val request = HttpRequest.newBuilder(URI.create("${props.baseUrl.trimEnd('/')}$PATH"))
            .timeout(props.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", internalToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return ChannelTestOutcome.NotifierUnavailable("알림 서비스 호출 실패: ${e.javaClass.simpleName}")
        }
        val json = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
        val errorMessage = json?.get("error")?.get("message")?.asText().orEmpty()
        return when (response.statusCode()) {
            200 -> json?.get("data")?.let { ChannelTestOutcome.Tested(it["result"].asText(), it["response"].asText()) }
                ?: ChannelTestOutcome.NotifierUnavailable("알림 서비스 응답을 읽을 수 없습니다.")
            503 -> ChannelTestOutcome.ChannelUnreachable(errorMessage)
            // 저장할 때 검사를 통과한 config 를 알림 서비스가 거절했다. 사용자에게는 "보내 봤더니 안 됐다"로 보여 준다
            400 -> ChannelTestOutcome.Tested(FAILED, errorMessage)
            else -> ChannelTestOutcome.NotifierUnavailable("알림 서비스 응답 ${response.statusCode()}")
        }
    }

    private companion object {
        const val PATH = "/internal/channels/test"
        const val FAILED = "FAILED"
    }
}

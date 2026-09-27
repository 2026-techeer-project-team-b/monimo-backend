package com.monimo.notifier.channel

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

// Slack Incoming Webhook. 공식 문서 기준(2026-09-27 확인, docs/alert/30-delivery.md):
//  - 초당 1건(짧은 몰림 허용), 넘으면 429 + Retry-After(초)
//  - 오류는 400/403/404 + 본문 코드(invalid_payload · channel_not_found 등) → 다시 보내도 안 됨
//  - 멱등 키 · 중복 제거 기능은 문서에 없다 → 워커가 재시도하면 같은 메시지가 두 번 갈 수 있다
@Component
class SlackWebhookSender(
    private val objectMapper: ObjectMapper,
    private val props: DeliveryHttpProperties,
) : NotificationSender {

    // 재시도는 워커 한 곳에서만. HttpClient 는 스스로 재시도하지 않고, 리다이렉트도 따라가지 않는다
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override val type = ChannelType.SLACK

    override fun send(message: OutboundMessage, config: Map<String, Any?>): SendResult {
        val url = config["webhook_url"] as? String ?: return SendResult.Permanent("config.webhook_url 없음")
        val body = objectMapper.writeValueAsString(mapOf("text" to message.summary()))
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(props.requestTimeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpConnectTimeoutException) {
            return SendResult.Retryable("연결 시간 초과")          // 요청이 나가기 전
        } catch (e: HttpTimeoutException) {
            return SendResult.Unknown("응답 시간 초과 ${props.requestTimeout}")  // 나갔는데 결과 모름
        } catch (e: ConnectException) {
            return SendResult.Retryable("연결 실패")
        } catch (e: IOException) {
            return SendResult.Unknown("입출력 오류: ${e.javaClass.simpleName}")
        }
        val text = sanitize(response.body())
        val status = response.statusCode()
        return when {
            status in 200..299 -> SendResult.Accepted(text)
            status == 429 -> SendResult.Retryable("429 $text", retryAfter(response))
            status >= 500 -> SendResult.Retryable("$status $text")
            else -> SendResult.Permanent("$status $text")
        }
    }

    private fun retryAfter(response: HttpResponse<*>): Duration? =
        response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.let(Duration::ofSeconds)

    // 응답 원문은 이력에 남기므로 길이를 자른다. 웹훅 주소(비밀값)는 애초에 넣지 않는다
    private fun sanitize(body: String?): String = (body ?: "").take(500)
}

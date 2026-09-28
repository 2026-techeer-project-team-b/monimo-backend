package com.monimo.notifier.channel

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monimo.notifier.support.FakeSlackServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

// Slack 어댑터는 응답을 분류만 한다. 재시도 여부는 워커의 RetryPolicy 가 정한다
class SlackWebhookSenderTest : BehaviorSpec({

    val fake = FakeSlackServer()
    afterSpec { fake.close() }

    val sender = SlackWebhookSender(
        jacksonObjectMapper(),
        DeliveryHttpProperties(connectTimeout = Duration.ofMillis(500), requestTimeout = Duration.ofMillis(500)),
    )
    val message = OutboundMessage(
        mapOf("transition" to "FIRING", "severity" to "CRITICAL", "rule_name" to "주문 서비스 5xx 급증", "service_name" to "order-service"),
    )
    fun send(mode: String) = sender.send(message, mapOf("webhook_url" to fake.url(mode)))

    Given("Slack 이 200 ok") {
        val result = send("ok")
        Then("Accepted, 보낸 본문은 text 필드 하나") {
            result shouldBe SendResult.Accepted("ok")
            fake.received.last() shouldContain "\"text\":\"[FIRING][CRITICAL] 주문 서비스 5xx 급증"
        }
    }

    Given("Slack 이 429 + Retry-After 30") {
        fake.retryAfterSeconds = "30"
        val result = send("429")
        Then("Retryable, Retry-After 를 초 단위로 읽는다") {
            (result as SendResult.Retryable).retryAfter shouldBe Duration.ofSeconds(30)
        }
    }

    Given("Slack 이 503") {
        Then("Retryable (Retry-After 없음)") {
            (send("503") as SendResult.Retryable).retryAfter shouldBe null
        }
    }

    Given("Slack 이 400 invalid_payload · 404 channel_not_found") {
        Then("둘 다 Permanent — 고치기 전에는 다시 보내도 안 된다") {
            (send("400") as SendResult.Permanent).reason shouldContain "invalid_payload"
            (send("gone") as SendResult.Permanent).reason shouldContain "404"
        }
    }

    Given("Slack 이 받고 나서 응답을 한도(0.5초)보다 늦게 준다") {
        fake.reset()
        fake.slowMillis = 1_500
        val result = send("slow")
        Then("Unknown — 실패가 아니라 결과를 모르는 것. 수신 서버는 이미 받았다") {
            result.shouldBeInstanceOf<SendResult.Unknown>()
            fake.received.size shouldBe 1
        }
    }

    Given("아무도 듣지 않는 주소") {
        val result = sender.send(message, mapOf("webhook_url" to "http://127.0.0.1:1/hook/ok"))
        Then("요청이 나가기 전 실패라서 Retryable") {
            result.shouldBeInstanceOf<SendResult.Retryable>()
        }
    }

    Given("config 에 webhook_url 이 없으면") {
        Then("Permanent") {
            sender.send(message, emptyMap()).shouldBeInstanceOf<SendResult.Permanent>()
        }
    }
})

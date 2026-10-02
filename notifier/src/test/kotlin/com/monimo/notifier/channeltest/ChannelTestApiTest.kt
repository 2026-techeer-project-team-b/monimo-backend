package com.monimo.notifier.channeltest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.notifier.support.FakeSlackServer
import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource

// 시험 발송 내부 문(#17)을 실제 HTTP 로 부르고, 가짜 Slack 이 실제로 받았는지 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
@TestPropertySource(
    properties = [
        "monimo.internal-token=test-internal-token",
        "monimo.notifier.delivery.enabled=false",
        "monimo.notifier.http.connect-timeout=500ms",
        "monimo.notifier.http.request-timeout=500ms",
    ],
)
class ChannelTestApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
) : BehaviorSpec({

    val fake = FakeSlackServer()
    afterSpec { fake.close() }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun test(body: Any?, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            token?.let { set("X-Internal-Token", it) }
        }
        val payload = if (body is String) body else objectMapper.writeValueAsString(body)
        return rest.exchange("/internal/channels/test", HttpMethod.POST, HttpEntity(payload, headers), String::class.java)
    }

    fun slack(url: String) = mapOf("type" to "SLACK", "config" to mapOf("webhook_url" to url, "channel" to "#backend-alert"))

    Given("Slack 이 받아 준다") {
        fake.reset()
        val response = test(slack(fake.url("ok")))

        Then("200 SUCCESS · 공급자 응답 ok, 가짜 Slack 이 [TEST] 메시지를 한 번 받았다") {
            response.statusCode.value() shouldBe 200
            json(response)["data"]["result"].asText() shouldBe "SUCCESS"
            json(response)["data"]["response"].asText() shouldBe "ok"
            fake.received.size shouldBe 1
            fake.received.first() shouldContain "[TEST]"
        }
    }

    Given("Slack 이 응답은 했지만 거절한다") {
        val notFound = test(slack(fake.url("gone")))
        val unavailable = test(slack(fake.url("503")))
        val limited = test(slack(fake.url("429")))

        Then("404 · 503 · 429 모두 200 FAILED — 닿긴 했으니 설정 · 공급자 쪽 문제다. 재시도는 하지 않는다") {
            listOf(notFound, unavailable, limited).forEach {
                it.statusCode.value() shouldBe 200
                json(it)["data"]["result"].asText() shouldBe "FAILED"
            }
            json(notFound)["data"]["response"].asText() shouldContain "404"
        }
    }

    Given("Slack 에 닿지 않는다") {
        fake.slowMillis = 1_500
        val refused = test(slack("http://127.0.0.1:1/hook/ok"))
        val slow = test(slack(fake.url("slow")))

        Then("연결 실패 · 응답 시간 초과는 503 UPSTREAM_UNAVAILABLE") {
            listOf(refused, slow).forEach {
                it.statusCode.value() shouldBe 503
                json(it)["error"]["code"].asText() shouldBe "UPSTREAM_UNAVAILABLE"
            }
        }
    }

    Given("요청 모양이 틀리다") {
        val badType = test(mapOf("type" to "SMS", "config" to mapOf("webhook_url" to fake.url("ok"))))
        val noConfig = test(mapOf("type" to "SLACK"))
        val noUrl = test(mapOf("type" to "SLACK", "config" to mapOf("channel" to "#a")))
        val badUrl = test(slack("not a url"))
        val broken = test("{")

        Then("모두 400 INVALID_REQUEST") {
            listOf(badType, noConfig, noUrl, badUrl, broken).forEach {
                it.statusCode.value() shouldBe 400
                json(it)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }
    }

    Given("어댑터가 아직 없는 유형(EMAIL)") {
        val response = test(mapOf("type" to "EMAIL", "config" to mapOf("to" to listOf("a@b.io"))))

        Then("200 FAILED · 미구현이라고 알려 준다 (성공으로 치지 않는다)") {
            response.statusCode.value() shouldBe 200
            json(response)["data"]["result"].asText() shouldBe "FAILED"
            json(response)["data"]["response"].asText() shouldContain "미구현"
        }
    }

    Given("내부 토큰이 없거나 틀리다") {
        fake.reset()
        val missing = test(slack(fake.url("ok")), token = null)
        val wrong = test(slack(fake.url("ok")), token = "nope")

        Then("401 이고 Slack 으로 아무것도 나가지 않는다") {
            missing.statusCode.value() shouldBe 401
            wrong.statusCode.value() shouldBe 401
            fake.hits.get() shouldBe 0
        }
    }
})

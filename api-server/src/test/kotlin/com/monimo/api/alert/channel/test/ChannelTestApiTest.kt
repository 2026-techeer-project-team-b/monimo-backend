package com.monimo.api.alert.channel.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.AlertChannelRepository
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.support.TestInfraConfig
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

// 가짜 알림 서비스. 받은 요청(토큰 · 본문)을 남기고, status · body 로 정한 대로 답한다. down 이면 문을 닫는다
class FakeNotifier : AutoCloseable {
    data class Received(val token: String?, val body: String)

    val received = ConcurrentLinkedQueue<Received>()
    @Volatile var status = 200
    @Volatile var body = """{"data":{"result":"SUCCESS","response":"ok"}}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/internal/channels/test") { ex ->
            received.add(Received(ex.requestHeaders.getFirst("X-Internal-Token"), ex.requestBody.readAllBytes().toString(Charsets.UTF_8)))
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

@TestConfiguration(proxyBeanMethods = false)
class ChannelTestTestConfig {
    @Bean(destroyMethod = "close") fun fakeNotifier() = FakeNotifier()

    // 실제 HTTP 클라이언트를 쓰되 주소만 가짜 알림 서비스로 돌린다
    @Bean @Primary
    fun testChannelTestClient(fake: FakeNotifier, props: NotifierClientProperties, objectMapper: ObjectMapper): ChannelTestClient =
        HttpChannelTestClient(props.copy(baseUrl = fake.baseUrl), "test-internal-token", objectMapper)
}

// 채널 시험 발송(#13)을 실제 HTTP 로 돈다. 알림 서비스는 가짜, PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class, ChannelTestTestConfig::class)
@TestPropertySource(properties = ["monimo.notifier.test-cooldown=1h"])
class ChannelTestApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    channels: AlertChannelRepository,
    passwordEncoder: PasswordEncoder,
    fake: FakeNotifier,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "admin@test-send.io", passwordEncoder.encode("pw"), "관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "viewer@test-send.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    val secretUrl = "https://hooks.slack.com/services/T000/B000/SECRETSECRET"
    fun channel(name: String, enabled: Boolean = true) = channels.save(
        AlertChannel(UUID.randomUUID(), name, ChannelType.SLACK, mapOf("webhook_url" to secretUrl, "channel" to "#a"), enabled, now, now),
    )

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun login(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pw"))
        return json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    val admin = login("admin@test-send.io")
    val viewer = login("viewer@test-send.io")

    fun test(uuid: UUID, bearer: String = admin): ResponseEntity<String> {
        val headers = HttpHeaders().apply { setBearerAuth(bearer) }
        return rest.exchange("/api/v1/alert-channels/$uuid/test", HttpMethod.POST, HttpEntity<Void>(headers), String::class.java)
    }

    fun respond(status: Int, body: String) {
        fake.status = status
        fake.body = body
    }

    Given("알림 서비스가 보내 보고 SUCCESS 를 준다") {
        respond(200, """{"data":{"result":"SUCCESS","response":"ok"}}""")
        fake.received.clear()
        val ch = channel("성공")
        val response = test(ch.alertChannelUuid)

        Then("200 · SUCCESS · 채널 UUID · 유형 · 시각이 오고, 응답에 웹훅 주소(비밀값)는 없다") {
            response.statusCode.value() shouldBe 200
            val data = json(response)["data"]
            data["alert_channel_uuid"].asText() shouldBe ch.alertChannelUuid.toString()
            data["type"].asText() shouldBe "SLACK"
            data["result"].asText() shouldBe "SUCCESS"
            data["response"].asText() shouldBe "ok"
            data.has("tested_at") shouldBe true
            response.body!! shouldNotContain "SECRETSECRET"
        }

        Then("알림 서비스에는 내부 토큰과 저장된 실제 config(가리지 않은 값)가 간다") {
            val sent = fake.received.single()
            sent.token shouldBe "test-internal-token"
            sent.body shouldContain secretUrl
            sent.body shouldContain "\"type\":\"SLACK\""
        }
    }

    Given("공급자가 거절했거나(FAILED) 채널 서버에 닿지 않았다(알림 서비스 503)") {
        respond(200, """{"data":{"result":"FAILED","response":"404 channel_not_found"}}""")
        val rejected = test(channel("거절").alertChannelUuid)
        respond(503, """{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"연결 실패"}}""")
        val unreachable = test(channel("닿지 않음").alertChannelUuid)

        Then("둘 다 200 FAILED — 시험 결과로 알려 준다") {
            json(rejected)["data"]["result"].asText() shouldBe "FAILED"
            json(rejected)["data"]["response"].asText() shouldBe "404 channel_not_found"
            unreachable.statusCode.value() shouldBe 200
            json(unreachable)["data"]["result"].asText() shouldBe "FAILED"
            json(unreachable)["data"]["response"].asText() shouldContain "채널 서버가 응답하지 않습니다"
        }
    }

    Given("알림 서비스 자체가 이상하다") {
        respond(401, """{"error":{"code":"UNAUTHENTICATED","message":"x"}}""")
        val unauthorized = test(channel("토큰 불일치").alertChannelUuid)
        respond(200, "not json")
        val garbage = test(channel("깨진 응답").alertChannelUuid)

        Then("503 UPSTREAM_UNAVAILABLE") {
            listOf(unauthorized, garbage).forEach {
                it.statusCode.value() shouldBe 503
                json(it)["error"]["code"].asText() shouldBe "UPSTREAM_UNAVAILABLE"
            }
        }

        When("알림 서비스가 살아난 뒤 같은 채널을 바로 다시 누르면") {
            val ch = channel("살아남")
            respond(500, """{}""")
            val down = test(ch.alertChannelUuid)
            respond(200, """{"data":{"result":"SUCCESS","response":"ok"}}""")
            val retried = test(ch.alertChannelUuid)

            Then("429 가 아니라 다시 보내진다 — 닿지 못한 시도는 간격에 세지 않는다") {
                down.statusCode.value() shouldBe 503
                retried.statusCode.value() shouldBe 200
            }
        }
    }

    Given("꺼진 채널") {
        respond(200, """{"data":{"result":"SUCCESS","response":"ok"}}""")
        val response = test(channel("꺼짐", enabled = false).alertChannelUuid)

        Then("시험할 수 있다 — 켜기 전에 설정을 확인하는 용도") {
            response.statusCode.value() shouldBe 200
        }
    }

    Given("같은 채널을 연달아 누른다 (간격 1시간으로 설정)") {
        respond(200, """{"data":{"result":"SUCCESS","response":"ok"}}""")
        val ch = channel("연타")
        val first = test(ch.alertChannelUuid)
        fake.received.clear()
        val second = test(ch.alertChannelUuid)

        Then("두 번째는 429 이고 알림 서비스를 부르지 않는다") {
            first.statusCode.value() shouldBe 200
            second.statusCode.value() shouldBe 429
            fake.received.size shouldBe 0
        }
    }

    Given("권한 · 대상이 틀리다") {
        val byViewer = test(channel("VIEWER 시도").alertChannelUuid, bearer = viewer)
        val missing = test(UUID.randomUUID())

        Then("VIEWER 403, 없는 채널 404") {
            byViewer.statusCode.value() shouldBe 403
            missing.statusCode.value() shouldBe 404
        }
    }
})

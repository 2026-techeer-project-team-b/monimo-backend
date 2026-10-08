package com.monimo.api.threaddump

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.common.security.InternalTokenProperties
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

// 가짜 수집기 한 대. POST /internal/thread-dump 에 정해 둔 상태 코드 · 본문으로 답하고, 받은 요청을 기록한다
class FakeCollector : AutoCloseable {
    data class Received(val token: String?, val body: String)

    val received = ConcurrentLinkedQueue<Received>()
    @Volatile var status = 503
    @Volatile var body = """{"code":"AGENT_NOT_REACHABLE","message":"이 수집기에 연결된 에이전트가 아닙니다."}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/internal/thread-dump") { ex ->
            received.add(Received(ex.requestHeaders.getFirst("X-Internal-Token"), ex.requestBody.readAllBytes().toString(Charsets.UTF_8)))
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class FakeCollectors : AutoCloseable {
    val a = FakeCollector()
    val b = FakeCollector()
    fun reset() {
        listOf(a, b).forEach { it.received.clear(); it.status = 503 }
    }
    val receivedCount get() = a.received.size + b.received.size
    override fun close() { a.close(); b.close() }
}

@TestConfiguration(proxyBeanMethods = false)
class ThreadDumpTestConfig {
    @Bean(destroyMethod = "close") fun fakeCollectors() = FakeCollectors()

    // 실제 HTTP 클라이언트를 쓰되 주소만 가짜 수집기 2대로 돌린다
    @Bean @Primary
    fun testCollectorClient(fakes: FakeCollectors, props: CollectorClientProperties, token: InternalTokenProperties): CollectorClient =
        HttpCollectorClient(props.copy(urls = listOf(fakes.a.baseUrl, fakes.b.baseUrl)), token.internalToken)
}

// 스레드 덤프 3개 문을 실제 HTTP 로 돈다. 수집기는 가짜 2대, PG · ClickHouse 는 Testcontainers. 서비스 이름 접두 td-
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class, ThreadDumpTestConfig::class)
class ThreadDumpApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    dataSource: DataSource,
    fakes: FakeCollectors,
    tokenProps: InternalTokenProperties,
) : BehaviorSpec({

    val jdbc = JdbcTemplate(dataSource)
    val now = Instant.parse("2026-10-08T09:00:00Z")
    users.save(User(UUID.randomUUID(), "td-admin@app.io", passwordEncoder.encode("pw"), "덤프관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "td-viewer@app.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    val order = applications.save(Application(UUID.randomUUID(), "td-order", null, null, now, now))
    val podUuid = UUID.randomUUID()
    jdbc.update(
        "INSERT INTO agents (agent_uuid, application_id, agent_key, hostname, status) VALUES (?, ?, ?, ?, 'UP')",
        podUuid, order.id, "td-order-pod-1", "td-order-pod-1",
    )

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    // TestRestTemplate 은 Apache HttpClient 5 를 써서 503 에 한 번 자동 재시도한다. 팬아웃 횟수를 세야 하므로 재시도 없는 클라이언트로 부른다
    val plain = RestTemplate(SimpleClientHttpRequestFactory()).apply {
        errorHandler = object : ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
        }
    }

    fun call(method: HttpMethod, path: String, bearer: String, body: Any? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(bearer)
        }
        return plain.exchange(rest.rootUri + path, method, HttpEntity(body?.let(objectMapper::writeValueAsString), headers), String::class.java)
    }

    fun login(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pw"))
        return json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    val admin = login("td-admin@app.io")
    val viewer = login("td-viewer@app.io")

    // Extension 이 보내는 결과 모양 그대로 (shop #34 ⓓ)
    fun extensionResult(threadCount: Int) = """
        {"service":"td-order","instance":"td-order-pod-1","taken_at":"2026-10-08T09:00:01Z","elapsed_ms":66,
         "thread_count":$threadCount,"format":"jstack","dump":"\"main\" #1 prio=5 os_prio=0\n   java.lang.Thread.State: RUNNABLE\n"}
    """.trimIndent()

    fun request(token: String, uuid: UUID = podUuid, timeoutMs: Long? = 3000) =
        call(HttpMethod.POST, "/api/v1/agents/$uuid/thread-dumps", token, mapOf("timeout_ms" to timeoutMs))

    lateinit var firstDumpUuid: String

    Given("덤프 요청 (15번)") {
        When("수집기 한 대만 에이전트를 쥐고 있으면") {
            fakes.reset()
            fakes.b.status = 200
            fakes.b.body = extensionResult(42)
            val response = request(admin)
            val data = json(response)["data"]

            Then("200 이고 그 한 대의 결과가 저장돼 돌아온다") {
                response.statusCode.value() shouldBe 200
                data["agent_uuid"].asText() shouldBe podUuid.toString()
                data["agent_key"].asText() shouldBe "td-order-pod-1"
                data["service_name"].asText() shouldBe "td-order"
                data["requested_by"].asText() shouldBe "td-admin@app.io"
                data["thread_count"].asInt() shouldBe 42
                data["dump"].asText().startsWith("\"main\" #1") shouldBe true
                firstDumpUuid = data["dump_uuid"].asText()
            }

            Then("두 대 모두에게 내부 토큰과 service · instance · timeout_ms 를 보냈다") {
                fakes.receivedCount shouldBe 2
                val sent = fakes.b.received.first()
                sent.token shouldBe tokenProps.internalToken
                val body = objectMapper.readTree(sent.body)
                body["service"].asText() shouldBe "td-order"
                body["instance"].asText() shouldBe "td-order-pod-1"
                body["timeout_ms"].asLong() shouldBe 3000
            }
        }

        When("모든 수집기가 503 이면") {
            fakes.reset()
            val response = request(admin)

            Then("한 번 더 팬아웃한 뒤 503 AGENT_NOT_REACHABLE") {
                response.statusCode.value() shouldBe 503
                json(response)["error"]["code"].asText() shouldBe "AGENT_NOT_REACHABLE"
                fakes.receivedCount shouldBe 4
            }
        }

        When("에이전트를 쥔 수집기가 504 를 주면") {
            fakes.reset()
            fakes.a.status = 504
            fakes.a.body = """{"code":"THREAD_DUMP_TIMEOUT","message":"늦음"}"""
            val response = request(admin)

            Then("재시도 없이 503 THREAD_DUMP_TIMEOUT") {
                response.statusCode.value() shouldBe 503
                json(response)["error"]["code"].asText() shouldBe "THREAD_DUMP_TIMEOUT"
                fakes.receivedCount shouldBe 2
            }
        }

        When("없는 파드로 부르면") {
            fakes.reset()
            val response = request(admin, uuid = UUID.randomUUID())

            Then("404 이고 수집기를 부르지 않는다") {
                response.statusCode.value() shouldBe 404
                fakes.receivedCount shouldBe 0
            }
        }

        When("VIEWER 가 부르면") {
            val response = request(viewer)

            Then("403 FORBIDDEN") {
                response.statusCode.value() shouldBe 403
                json(response)["error"]["code"].asText() shouldBe "FORBIDDEN"
            }
        }
    }

    Given("덤프 목록 (16번)") {
        // 한 건 더 만들어 두 건이 되게 한다
        fakes.reset()
        fakes.a.status = 200
        fakes.a.body = extensionResult(7)
        val second = json(request(admin))["data"]["dump_uuid"].asText()

        When("agent_key 로 거르면") {
            val data = json(call(HttpMethod.GET, "/api/v1/thread-dumps?agent_key=td-order-pod-1&limit=500", viewer))["data"]

            Then("최근순 두 건이고 본문은 없다") {
                data.map { it["dump_uuid"].asText() } shouldContainExactly listOf(second, firstDumpUuid)
                data.first().has("dump") shouldBe false
                data.first()["thread_count"].asInt() shouldBe 7
            }
        }

        When("limit 1 로 두 쪽을 읽으면") {
            val first = json(call(HttpMethod.GET, "/api/v1/thread-dumps?service_name=td-order&limit=1", viewer))
            val cursor = first["page"]["next_cursor"].asText()
            val next = json(call(HttpMethod.GET, "/api/v1/thread-dumps?service_name=td-order&limit=1&cursor=$cursor", viewer))

            Then("겹치지 않고 이어진다") {
                first["data"].map { it["dump_uuid"].asText() } shouldContainExactly listOf(second)
                next["data"].map { it["dump_uuid"].asText() } shouldContainExactly listOf(firstDumpUuid)
                next["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("from 만 보내면") {
            val response = call(HttpMethod.GET, "/api/v1/thread-dumps?from=2026-10-08T00:00:00Z", viewer)

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }
    }

    Given("덤프 상세 (17번)") {
        When("있는 uuid 로 부르면") {
            val data = json(call(HttpMethod.GET, "/api/v1/thread-dumps/$firstDumpUuid", viewer))["data"]

            Then("본문과 agent_uuid 까지 나온다") {
                data["dump_uuid"].asText() shouldBe firstDumpUuid
                data["agent_uuid"].asText() shouldBe podUuid.toString()
                data["thread_count"].asInt() shouldBe 42
                data["dump"].asText().contains("RUNNABLE") shouldBe true
            }
        }

        When("없는 uuid 로 부르면") {
            val response = call(HttpMethod.GET, "/api/v1/thread-dumps/${UUID.randomUUID()}", viewer)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }
})

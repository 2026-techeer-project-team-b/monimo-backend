package com.monimo.api.selfcheck

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeLessThan
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
import org.springframework.http.ResponseEntity
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress

// 가짜 관리 포트 한 대. /actuator/metrics/{게이지} 에 정해 둔 값으로 답한다. value 가 null 이면 404 (게이지 없음 · 모듈 죽음)
class FakeGauge : AutoCloseable {
    @Volatile var value: Double? = 0.0

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/actuator/metrics/") { ex ->
            val v = value
            val bytes = if (v == null) ByteArray(0) else """{"name":"x","measurements":[{"statistic":"VALUE","value":$v}]}""".toByteArray()
            ex.sendResponseHeaders(if (v == null) 404 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class FakeGauges : AutoCloseable {
    val detector = FakeGauge()
    val collector = FakeGauge()
    override fun close() { detector.close(); collector.close() }
}

@TestConfiguration(proxyBeanMethods = false)
class SelfCheckTestConfig {
    @Bean(destroyMethod = "close") fun fakeGauges() = FakeGauges()

    // 실제 HTTP 읽기(HttpGaugeReader)를 쓰되 주소만 게이지 이름으로 가짜 둘에 돌린다
    @Bean @Primary
    fun testGaugeReader(fakes: FakeGauges, props: SelfCheckProperties, objectMapper: ObjectMapper): GaugeReader {
        val http = HttpGaugeReader(props.gaugeTimeout, objectMapper)
        return GaugeReader { _, gauge -> http.read(if (gauge.startsWith("monimo.detector")) fakes.detector.baseUrl else fakes.collector.baseUrl, gauge) }
    }
}

// 자기 점검 문 (#226). 게이지는 가짜 관리 포트 2대, PG 는 Testcontainers 라 마지막 Given 에서 멈출 수 있다
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // PG 기한 2초는 Hikari 를 3초로 줄인 것보다 짧다 : 기한이 먼저 와야 "timeout" 이 나온다
    properties = [
        "monimo.internal-token=test-internal-token",
        "spring.datasource.hikari.connection-timeout=3000",
        "monimo.self-check.postgres-timeout=2s",
    ],
)
@Import(TestInfraConfig::class, SelfCheckTestConfig::class)
class SelfCheckApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    fakes: FakeGauges,
    postgres: PostgreSQLContainer<*>,
) : BehaviorSpec({

    fun call(token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/self-check", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }
    fun data(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)["data"]

    Given("PG 가 붙어 있고 탐지 · 수집기 게이지가 작다") {
        fakes.detector.value = 12.3
        fakes.collector.value = 8.0

        When("내부 토큰으로 부르면") {
            val response = call()
            val d = data(response)

            Then("200 이고 셋 다 ok, PG 는 ms 가 있고 게이지 값이 그대로 온다") {
                response.statusCode.value() shouldBe 200
                d["postgres"]["ok"].asBoolean() shouldBe true
                d["postgres"]["ms"].asLong() shouldBeLessThan 2000
                d["detector"]["ok"].asBoolean() shouldBe true
                d["detector"]["age_sec"].asDouble() shouldBe 12.3
                d["detector"]["max_sec"].asLong() shouldBe 180
                d["collector"]["ok"].asBoolean() shouldBe true
                d["collector"]["age_sec"].asDouble() shouldBe 8.0
            }
        }

        When("토큰 없이 부르면") {
            Then("401") {
                call(token = null).statusCode.value() shouldBe 401
            }
        }
    }

    Given("탐지 게이지가 180초를 넘고 수집기 게이지는 못 읽는다") {
        fakes.detector.value = 251.0
        fakes.collector.value = null

        When("부르면") {
            val response = call()
            val d = data(response)

            Then("HTTP 는 200 그대로, 탐지는 ok=false 에 값, 수집기는 ok=false 에 unreachable") {
                response.statusCode.value() shouldBe 200
                d["detector"]["ok"].asBoolean() shouldBe false
                d["detector"]["age_sec"].asDouble() shouldBe 251.0
                d["collector"]["ok"].asBoolean() shouldBe false
                d["collector"]["age_sec"].isNull shouldBe true
                d["collector"]["error"].asText() shouldBe "unreachable"
            }
        }
    }

    Given("PostgreSQL 이 멈췄다") {
        fakes.detector.value = 5.0
        fakes.collector.value = 5.0
        postgres.stop()

        When("부르면") {
            val started = System.currentTimeMillis()
            val response = call()
            val took = System.currentTimeMillis() - started
            val d = data(response)

            Then("기한(2초) 안에 200 으로 답하고 postgres 만 ok=false timeout, 게이지 둘은 그대로 ok") {
                response.statusCode.value() shouldBe 200
                took shouldBeLessThan 10_000
                d["postgres"]["ok"].asBoolean() shouldBe false
                d["postgres"]["error"].asText() shouldBe "timeout"
                d["detector"]["ok"].asBoolean() shouldBe true
                d["collector"]["ok"].asBoolean() shouldBe true
            }
        }
    }
})

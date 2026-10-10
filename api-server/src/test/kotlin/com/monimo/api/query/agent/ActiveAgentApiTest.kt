package com.monimo.api.query.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

// spans · metrics_raw 에 줄을 넣고 탐지가 부르는 내부 문을 실제 HTTP 로 부른다. ClickHouse 는 Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token"],
)
@Import(TestInfraConfig::class)
class ActiveAgentApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    // 10분 전, 초 단위로 맞춘 시각
    val t0 = Instant.ofEpochSecond(Instant.now().epochSecond - 600)
    var seq = 0

    fun span(service: String, agentId: String, at: Instant) {
        seq++
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('t$seq', 's$seq', '', fromUnixTimestamp64Milli(${at.toEpochMilli()}, 'UTC'), 1000000, '$service', '$agentId', 'GET /x', 'SERVER', 'UNSET', 200)
            """.trimIndent(),
        )
    }

    fun metric(service: String, agentId: String, at: Instant) {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
            VALUES ('$service', '$agentId', 'jvm.thread.count', 1, map(), toDateTime(${at.epochSecond}, 'UTC'), 10)
            """.trimIndent(),
        )
    }

    // order-a: 스팬(+10초) 뒤에 메트릭(+40초) → 메트릭이 마지막. 구간 끝(+60초)에 걸친 스팬은 세지 않는다
    span("shop-order", "order-a", t0.plusSeconds(10))
    metric("shop-order", "order-a", t0.plusSeconds(40))
    span("shop-order", "order-a", t0.plusSeconds(60))
    // order-b: 메트릭(+20초) 뒤에 스팬(+50.5초) → 스팬이 마지막
    metric("shop-order", "order-b", t0.plusSeconds(20))
    span("shop-order", "order-b", t0.plusMillis(50_500))
    // pay-c: 메트릭만
    metric("shop-payment", "pay-c", t0.plusSeconds(30))
    // 구간보다 앞선 파드, 파드 식별자가 빈 줄
    span("shop-payment", "pay-old", t0.minusSeconds(120))
    span("shop-order", "", t0.plusSeconds(15))

    fun get(query: String, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/agents/active?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val range = "from=$t0&to=${t0.plusSeconds(60)}"

    Given("탐지가 agents/active 를 부를 때") {
        When("service_name 없이 구간만 주면") {
            val response = get(range)
            val rows = json(response)["data"]

            Then("구간 안에 데이터를 보낸 파드가 서비스 · 파드 순으로 한 줄씩 온다") {
                response.statusCode.value() shouldBe 200
                rows.map { "${it["service_name"].asText()}/${it["agent_key"].asText()}" } shouldBe listOf(
                    "shop-order/order-a",
                    "shop-order/order-b",
                    "shop-payment/pay-c",
                )
            }

            Then("스팬 · 메트릭 중 더 최근 시각과 그 표 이름을 준다") {
                Instant.parse(rows[0]["last_signal_at"].asText()) shouldBe t0.plusSeconds(40)
                rows[0]["source"].asText() shouldBe "metrics_raw"
                Instant.parse(rows[1]["last_signal_at"].asText()) shouldBe t0.plusMillis(50_500)
                rows[1]["source"].asText() shouldBe "spans"
                rows[2]["source"].asText() shouldBe "metrics_raw"
            }
        }

        When("service_name 을 주면") {
            val rows = json(get("$range&service_name=shop-payment"))["data"]

            Then("그 서비스 파드만 온다") {
                rows.map { it["agent_key"].asText() } shouldBe listOf("pay-c")
            }
        }

        When("아무도 데이터를 보내지 않은 구간이면") {
            val response = get("from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}")

            Then("200 에 빈 목록") {
                response.statusCode.value() shouldBe 200
                json(response)["data"].size() shouldBe 0
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("내부 토큰이 없으면") {
            Then("401") {
                get(range, token = null).statusCode.value() shouldBe 401
            }
        }
    }
})

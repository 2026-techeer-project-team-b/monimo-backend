package com.monimo.api.query.metric

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
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

// metrics_raw 에 GC 총합을 넣으면 MV 가 metrics_1m 을 채우고, 탐지용 내부 문이 1분 증가량으로 바꿔 준다
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token"],
)
@Import(TestInfraConfig::class)
class GcTimeApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    // 1분 경계에 맞춘 30분 전 시각
    val t0 = Instant.ofEpochSecond((Instant.now().epochSecond - 1800) / 60 * 60)

    fun total(offsetSec: Long, value: Double, name: String, hash: Int, agent: String = "pod-a", service: String = "gc-order", counter: Int = 1) {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, is_counter, series_hash, attributes, ts, value)
            VALUES ('$service', '$agent', '$name', $counter, $hash, map(), toDateTime(${t0.plusSeconds(offsetSec).epochSecond}, 'UTC'), $value)
            """.trimIndent(),
        )
    }

    val sum = "jvm.gc.duration.sum"
    val count = "jvm.gc.duration.count"

    // pod-a young GC 시간(초): 범위 앞 1.0 → 1.04 → 1.10 → 0.01(재시작) → 0.03
    total(-60, 1.0, sum, 21)
    total(0, 1.04, sum, 21)
    total(60, 1.10, sum, 21)
    total(120, 0.01, sum, 21)
    total(180, 0.03, sum, 21)
    // pod-a old GC 시간: 2.0 → 2.5 → 2.5
    total(-60, 2.0, sum, 22)
    total(0, 2.5, sum, 22)
    total(60, 2.5, sum, 22)
    // pod-a GC 횟수: 10 → 13 → 15
    total(-60, 10.0, count, 23)
    total(0, 13.0, count, 23)
    total(60, 15.0, count, 23)
    // 총합이 아닌 줄(.max)과 표시 없는 옛 줄은 안 센다
    total(0, 0.9, "jvm.gc.duration.max", 24, counter = 0)
    total(-60, 0.0, sum, 25, agent = "pod-b", counter = 0)
    total(0, 9.0, sum, 25, agent = "pod-b", counter = 0)
    // 다른 서비스
    total(-60, 0.0, sum, 26, agent = "pod-p", service = "gc-payment")
    total(0, 0.2, sum, 26, agent = "pod-p", service = "gc-payment")

    fun get(query: String, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/metrics/gc-time?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val range = "from=$t0&to=${t0.plusSeconds(240)}"

    Given("탐지가 GC_TIME 재료를 읽을 때") {
        When("서비스를 주면") {
            val response = get("service_name=gc-order&$range")
            val rows = json(response)["data"]

            Then("파드마다 1분씩, GC 종류별로 뺀 뒤 더한 시간(ms)과 횟수를 준다") {
                response.statusCode.value() shouldBe 200
                rows.map { Instant.parse(it["ts_min"].asText()) } shouldBe listOf(t0, t0.plusSeconds(60), t0.plusSeconds(180))
                rows[0]["agent_key"].asText() shouldBe "pod-a"
                rows[0]["service_name"].asText() shouldBe "gc-order"
                rows[0]["gc_time_ms"].asDouble() shouldBe 540.0
                rows[0]["gc_count"].asLong() shouldBe 3
                rows[1]["gc_time_ms"].asDouble() shouldBe (60.0 plusOrMinus 0.001)
                rows[1]["gc_count"].asLong() shouldBe 2
            }

            Then("재시작으로 음수가 된 1분은 빠지고, 다음 1분부터 다시 뺀다") {
                rows[2]["gc_time_ms"].asDouble() shouldBe (20.0 plusOrMinus 0.001)
                rows[2]["gc_count"].asLong() shouldBe 0
            }

            Then("총합 표시가 없는 줄은 안 센다") {
                rows.none { it["agent_key"].asText() == "pod-b" } shouldBe true
            }
        }

        When("서비스를 안 주면") {
            Then("모든 서비스의 파드가 온다") {
                val rows = json(get(range))["data"].filter { it["service_name"].asText().startsWith("gc-") }
                rows.map { it["service_name"].asText() to it["agent_key"].asText() }.toSet() shouldBe
                    setOf("gc-order" to "pod-a", "gc-payment" to "pod-p")
                rows.first { it["service_name"].asText() == "gc-payment" }["gc_time_ms"].asDouble() shouldBe (200.0 plusOrMinus 0.001)
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("service_name=gc-order&from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("내부 토큰이 없으면") {
            Then("401") {
                get(range, token = null).statusCode.value() shouldBe 401
            }
        }
    }
})

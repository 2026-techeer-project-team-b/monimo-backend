package com.monimo.api.query.health

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeInRange
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

// spans 에 줄을 넣으면 MV 가 service_health_1m 을 채우고, 그걸 내부 문으로 읽는다. ClickHouse 는 Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token"],
)
@Import(TestInfraConfig::class)
class ServiceHealthApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    // 5분 경계에 맞춘 30분 전 시각. step 300 버킷이 이 시각에서 시작한다
    val t0 = Instant.ofEpochSecond((Instant.now().epochSecond - 1800) / 300 * 300)

    fun span(service: String, at: Instant, durationMs: Long, httpStatus: Int, kind: String = "SERVER") {
        val status = if (httpStatus >= 500) "ERROR" else "UNSET"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('t-${at.toEpochMilli()}-$durationMs', 's-$durationMs', '', fromUnixTimestamp64Milli(${at.toEpochMilli()}, 'UTC'),
                    ${durationMs * 1_000_000}, '$service', '$service-pod', 'GET /x', '$kind', '$status', $httpStatus)
            """.trimIndent(),
        )
    }

    // t0 분: shop-order 4건(200 · 200 · 404 · 500) + 부른 쪽 스팬 1건(세지 않음), shop-payment 1건(503, 100ms)
    // t0 + 1분: shop-order 2건. t0 + 2분: 요청 없음
    span("shop-order", t0.plusSeconds(1), 10, 200)
    span("shop-order", t0.plusSeconds(2), 20, 200)
    span("shop-order", t0.plusSeconds(3), 30, 404)
    span("shop-order", t0.plusSeconds(4), 1000, 500)
    span("shop-order", t0.plusSeconds(5), 7, 200, kind = "CLIENT")
    span("shop-payment", t0.plusSeconds(6), 100, 503)
    span("shop-order", t0.plusSeconds(61), 50, 200)
    span("shop-order", t0.plusSeconds(62), 60, 200)

    fun get(query: String, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/service-health?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val range = "from=$t0&to=${t0.plusSeconds(180)}"

    Given("탐지가 service-health 를 부를 때") {
        When("step 기본값(60)으로 3분을 읽으면") {
            val response = get(range)
            val rows = json(response)["data"]

            Then("요청이 있던 버킷만 시각 · 서비스 순으로 온다 (요청 0건인 3번째 분은 행이 없다)") {
                response.statusCode.value() shouldBe 200
                rows.map { it["ts_min"].asText() to it["service_name"].asText() } shouldBe listOf(
                    t0.toString() to "shop-order",
                    t0.toString() to "shop-payment",
                    t0.plusSeconds(60).toString() to "shop-order",
                )
            }

            Then("호출 · 에러 · 4xx · 5xx 를 명세 필드 이름으로 센다 (부른 쪽 스팬은 빠진다)") {
                val order = rows[0]
                order["cnt"].asLong() shouldBe 4
                order["err_cnt"].asLong() shouldBe 1
                order["cnt_4xx"].asLong() shouldBe 1
                order["cnt_5xx"].asLong() shouldBe 1
            }

            Then("지연 백분위는 나노초가 아니라 ms 다") {
                val payment = rows[1]
                payment["p50_ms"].asLong() shouldBe 100
                payment["p95_ms"].asLong() shouldBe 100
                payment["p99_ms"].asLong() shouldBe 100
                rows[0]["p99_ms"].asInt() shouldBeInRange 30..1000
            }
        }

        When("step 300 으로 읽으면") {
            val rows = json(get("$range&step=300"))["data"]

            Then("5분 버킷 하나로 다시 합친다") {
                rows.size() shouldBe 2
                rows[0]["ts_min"].asText() shouldBe t0.toString()
                rows[0]["service_name"].asText() shouldBe "shop-order"
                rows[0]["cnt"].asLong() shouldBe 6
            }
        }

        When("service_name 으로 거르면") {
            val rows = json(get("$range&service_name=shop-payment"))["data"]

            Then("그 서비스만 온다") {
                rows.map { it["service_name"].asText() } shouldBe listOf("shop-payment")
                rows[0]["cnt_5xx"].asLong() shouldBe 1
            }
        }

        When("데이터가 없는 구간이면") {
            val response = get("from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}")

            Then("200 에 빈 목록") {
                response.statusCode.value() shouldBe 200
                json(response)["data"].size() shouldBe 0
            }
        }

        When("step 이 60 의 배수가 아니거나 60 미만이면") {
            Then("400 INVALID_REQUEST") {
                json(get("$range&step=90"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
                get("$range&step=30").statusCode.value() shouldBe 400
            }
        }

        When("from 이 to 보다 늦으면") {
            val response = get("from=${t0.plusSeconds(180)}&to=$t0")

            Then("422 UNPROCESSABLE") {
                response.statusCode.value() shouldBe 422
                json(response)["error"]["code"].asText() shouldBe "UNPROCESSABLE"
            }
        }

        When("내부 토큰이 없으면") {
            val response = get(range, token = null)

            Then("401") {
                response.statusCode.value() shouldBe 401
            }
        }
    }
})

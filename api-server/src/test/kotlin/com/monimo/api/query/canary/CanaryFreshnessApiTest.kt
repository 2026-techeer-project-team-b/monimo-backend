package com.monimo.api.query.canary

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeInRange
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

// spans 에 카나리 표식이 있는 스팬을 넣고 파수꾼이 부르는 내부 문을 실제 HTTP 로 부른다. ClickHouse 는 Testcontainers
// 기준을 60초로 줄여 "기준 안 · 밖" 을 둘 다 만든다
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token", "monimo.canary.threshold-sec=60"],
)
@Import(TestInfraConfig::class)
class CanaryFreshnessApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    var seq = 0

    fun span(service: String, secondsAgo: Long, canary: Boolean) {
        seq++
        val attributes = if (canary) "map('monimo.canary', 'true')" else "map()"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind,
                               status_code, http_status, attributes)
            VALUES ('cn-$seq', 's$seq', '', fromUnixTimestamp64Milli(${now.minusSeconds(secondsAgo).toEpochMilli()}, 'UTC'), 1000000,
                    '$service', '$service-pod', 'POST /orders', 'SERVER', 'UNSET', 200, $attributes)
            """.trimIndent(),
        )
    }

    // cn-fresh: 10초 전 카나리 (그보다 앞선 카나리도 있다). 표식 없는 더 최근 스팬은 카나리가 아니다
    span("cn-fresh", 300, canary = true)
    span("cn-fresh", 10, canary = true)
    span("cn-fresh", 1, canary = false)
    // cn-stale: 마지막 카나리가 5분 전 (기준 60초 밖). cn-plain: 카나리가 한 번도 없다
    span("cn-stale", 300, canary = true)
    span("cn-plain", 5, canary = false)
    // 하루보다 오래된 카나리는 조회 구간 밖이다
    span("cn-old", 90_000, canary = true)

    fun get(query: String, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/canary/freshness$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun data(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)["data"]

    Given("파수꾼이 카나리 신선도를 물을 때") {
        When("그 서비스의 최근 카나리가 기준 안이면") {
            val response = get("?service_name=cn-fresh")
            val data = data(response)

            Then("200 에 fresh = true 와 가장 최근 카나리 시각을 준다 (표식 없는 스팬은 세지 않는다)") {
                response.statusCode.value() shouldBe 200
                data["service_name"].asText() shouldBe "cn-fresh"
                data["fresh"].asBoolean() shouldBe true
                data["threshold_sec"].asLong() shouldBe 60
                data["age_sec"].asLong() shouldBeInRange 10L..30L
                Instant.parse(data["last_signal_at"].asText()).toEpochMilli() shouldBe now.minusSeconds(10).toEpochMilli()
            }
        }

        When("마지막 카나리가 기준보다 오래됐으면") {
            val data = data(get("?service_name=cn-stale"))

            Then("fresh = false 이고 몇 초 전인지 알려 준다") {
                data["fresh"].asBoolean() shouldBe false
                data["age_sec"].asLong() shouldBeInRange 300L..330L
            }
        }

        When("그 서비스에 카나리가 한 번도 없었으면") {
            val response = get("?service_name=cn-plain")
            val data = data(response)

            Then("404 가 아니라 200 에 fresh = false, 시각과 경과 초는 null") {
                response.statusCode.value() shouldBe 200
                data["fresh"].asBoolean() shouldBe false
                data["last_signal_at"].isNull shouldBe true
                data["age_sec"].isNull shouldBe true
                data["threshold_sec"].asLong() shouldBe 60
                data["service_name"].asText() shouldBe "cn-plain"
            }
        }

        When("마지막 카나리가 하루보다 오래됐으면") {
            val data = data(get("?service_name=cn-old"))

            Then("조회 구간 밖이라 없는 것으로 답한다") {
                data["fresh"].asBoolean() shouldBe false
                data["last_signal_at"].isNull shouldBe true
            }
        }

        When("service_name 을 주지 않으면") {
            val data = data(get(""))

            Then("모든 서비스 중 가장 최근 카나리와 그 서비스 이름을 준다") {
                data["service_name"].asText() shouldBe "cn-fresh"
                data["fresh"].asBoolean() shouldBe true
            }
        }

        When("내부 토큰이 없으면") {
            Then("401") {
                get("?service_name=cn-fresh", token = null).statusCode.value() shouldBe 401
            }
        }
    }
})

package com.monimo.api.query.stats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// spans 에 받은 요청(SERVER 스팬)을 넣으면 MV 가 url_stats_1m 을 채우고, 그걸 GET /stats/urls 로 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class UrlStatApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@urls.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "us-order", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@urls.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/stats/urls?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // 1분 경계에 맞춘 10분 전 시각
    val t0 = Instant.ofEpochSecond((now.epochSecond - 600) / 60 * 60)
    var seq = 0

    fun span(name: String, offsetSec: Long, durationMs: Long, error: Boolean = false, agent: String = "pod-a", service: String = "us-order", kind: String = "SERVER") {
        seq++
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('us-$seq', 's-$seq', '', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), ${durationMs * 1_000_000},
                    '$service', '$agent', '$name', '$kind', '${if (error) "ERROR" else "UNSET"}', ${if (error) 500 else 200})
            """.trimIndent(),
        )
    }

    // GET 4건(1분 걸쳐, pod-b 1건, 실패 1) · DELETE 3건(모두 50ms, 실패 1) · POST 2건(실패 1)
    span("GET /orders/{id}", 1, 10)
    span("GET /orders/{id}", 2, 20)
    span("GET /orders/{id}", 61, 30, error = true)
    span("GET /orders/{id}", 62, 40, agent = "pod-b")
    span("DELETE /orders/{id}", 3, 50)
    span("DELETE /orders/{id}", 4, 50, error = true)
    span("DELETE /orders/{id}", 5, 50)
    span("POST /orders", 6, 100)
    span("POST /orders", 7, 300, error = true)
    // 부른 쪽(CLIENT) 스팬과 다른 서비스는 안 센다
    span("POST /payments", 8, 900, kind = "CLIENT")
    span("GET /orders/{id}", 9, 10, service = "us-payment")

    val base = "service_name=us-order&from=$t0&to=${t0.plusSeconds(300)}"

    fun names(response: ResponseEntity<String>) = json(response)["data"].map { it["span_name"].asText() }

    Given("화면이 URL 통계를 열 때") {
        When("서비스와 시간 범위를 주면") {
            val response = get(base)
            val data = json(response)["data"]

            Then("받은 요청만 URL 마다 합쳐 호출 많은 순으로 준다 (1분 칸을 넘어도 하나로)") {
                response.statusCode.value() shouldBe 200
                names(response) shouldBe listOf("GET /orders/{id}", "DELETE /orders/{id}", "POST /orders")
                data.map { it["cnt"].asLong() } shouldBe listOf(4L, 3L, 2L)
                json(response)["page"]["next_cursor"].isNull shouldBe true
            }

            Then("에러 수와 에러율(소수 넷째 자리)을 준다") {
                data.map { it["err_cnt"].asLong() } shouldBe listOf(1L, 1L, 1L)
                data.map { it["error_rate"].asDouble() } shouldBe listOf(0.25, 0.3333, 0.5)
            }

            Then("응답시간 백분위를 ms 정수로 준다") {
                data[1]["p50_ms"].asLong() shouldBe 50
                data[1]["p95_ms"].asLong() shouldBe 50
                data[1]["p99_ms"].asLong() shouldBe 50
                data[0]["p50_ms"].asLong() shouldBeInRange 10L..40L
                data[0]["p99_ms"].asLong() shouldBeInRange data[0]["p50_ms"].asLong()..40L
            }
        }

        When("agent_key 를 주면") {
            Then("그 파드가 받은 요청만 센다") {
                val data = json(get("$base&agent_key=pod-b"))["data"]
                data.map { it["span_name"].asText() to it["cnt"].asLong() } shouldBe listOf("GET /orders/{id}" to 1L)
            }
        }

        When("limit 1 로 나눠 읽으면") {
            val first = get("$base&limit=1")
            val second = get("$base&limit=1&cursor=${json(first)["page"]["next_cursor"].asText()}")
            val third = get("$base&limit=1&cursor=${json(second)["page"]["next_cursor"].asText()}")

            Then("겹치지 않고 이어지며 마지막 쪽은 next_cursor 가 null (호출 수가 같으면 이름 순)") {
                (names(first) + names(second) + names(third)) shouldBe listOf("GET /orders/{id}", "DELETE /orders/{id}", "POST /orders")
                json(third)["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("요청이 없는 구간이면") {
            Then("404 가 아니라 200 에 빈 목록") {
                val response = get("service_name=us-order&from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}")
                response.statusCode.value() shouldBe 200
                json(response)["data"].size() shouldBe 0
            }
        }

        When("값이 틀리면") {
            Then("등록되지 않은 서비스는 404 NOT_FOUND") {
                get("service_name=us-payment&from=$t0&to=${t0.plusSeconds(300)}").statusCode.value() shouldBe 404
            }

            Then("service_name 이 빠지면 400") {
                get("from=$t0&to=${t0.plusSeconds(300)}").statusCode.value() shouldBe 400
            }

            Then("cursor 가 깨져 있거나 limit 이 범위를 벗어나면 400") {
                get("$base&cursor=!!!").statusCode.value() shouldBe 400
                json(get("$base&limit=501"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }

            Then("from 이 to 보다 늦으면 422 UNPROCESSABLE") {
                get("service_name=us-order&from=${t0.plusSeconds(300)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(base, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

package com.monimo.api.query.error

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// spans 에 실패 스팬을 넣고 GET /errors/timeline 을 실제 HTTP 로 부른다. ClickHouse · PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ErrorTimelineApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@timeline.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "tl-payment", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@timeline.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(path: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/errors$path", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // 5분 경계에 맞춘 30분 전. step 300 칸이 이 시각에서 시작한다
    val t0 = Instant.ofEpochSecond((now.epochSecond - 1800) / 300 * 300)

    fun span(id: String, offsetSec: Long, httpStatus: Int, status: String = "ERROR", exceptionType: String? = null) {
        val at = t0.plusSeconds(offsetSec).toEpochMilli()
        val events = exceptionType
            ?.let { "[fromUnixTimestamp64Milli($at, 'UTC')], ['exception'], [map('exception.type', '$it')]" }
            ?: "[], [], []"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind,
                               status_code, http_status, events.ts, events.name, events.attributes)
            VALUES ('trace-$id', '$id', '', fromUnixTimestamp64Milli($at, 'UTC'), 4000000, 'tl-payment', 'pod-1', 'POST /payments', 'SERVER',
                    '$status', $httpStatus, $events)
            """.trimIndent(),
        )
    }

    // 첫 1분: 5xx 3건(Timeout 2 · Gateway 1) · 4xx 1건 · HTTP 아닌 실패 1건 · 정상 1건
    span("a1", 1, 500, exceptionType = "TimeoutException")
    span("a2", 2, 500, exceptionType = "TimeoutException")
    span("a3", 3, 503, exceptionType = "GatewayException")
    span("a4", 4, 404)
    span("a5", 5, 0)
    span("a6", 6, 200, status = "UNSET")
    // 둘째 1분: 1건(두 번 적재). 넷째 1분: 1건
    span("b1", 61, 500, exceptionType = "TimeoutException")
    span("b1", 61, 500, exceptionType = "TimeoutException")
    span("c1", 200, 500, exceptionType = "TimeoutException")

    val base = "service_name=tl-payment&from=$t0&to=${t0.plusSeconds(300)}"

    fun cells(response: ResponseEntity<String>) = json(response)["data"]["series"].map {
        val type = if (it["exception_type"].isNull) "null" else it["exception_type"].asText()
        "${Instant.parse(it["ts_min"].asText()).epochSecond - t0.epochSecond}|${it["http_status_class"].asText()}|$type|${it["cnt"].asLong()}"
    }

    Given("화면이 에러 타임라인을 그릴 때") {
        When("step 기본값(60)으로 5분을 읽으면") {
            val response = get("/timeline?$base")

            Then("1분 칸 × 상태코드 대역 × 예외 타입으로 세고, 에러가 없는 칸은 없다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["step"].asInt() shouldBe 60
                cells(response) shouldBe listOf(
                    "0|4xx|null|1",
                    "0|5xx|GatewayException|1",
                    "0|5xx|TimeoutException|2",
                    "0|other|null|1",
                    "60|5xx|TimeoutException|1",
                    "180|5xx|TimeoutException|1",
                )
            }

            Then("합계가 에러 목록의 줄 수와 같다 (정상 스팬 제외 · 중복 적재는 한 번)") {
                val total = json(response)["data"]["series"].sumOf { it["cnt"].asLong() }
                total shouldBe json(get("?$base"))["data"].size().toLong()
                total shouldBe 7
            }
        }

        When("step 300 으로 읽으면") {
            val response = get("/timeline?$base&step=300")

            Then("5분 칸 하나로 합친다") {
                json(response)["data"]["step"].asInt() shouldBe 300
                cells(response) shouldBe listOf(
                    "0|4xx|null|1",
                    "0|5xx|GatewayException|1",
                    "0|5xx|TimeoutException|4",
                    "0|other|null|1",
                )
            }
        }

        When("에러가 없는 구간이면") {
            val response = get("/timeline?service_name=tl-payment&from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}")

            Then("200 에 빈 series") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["series"].size() shouldBe 0
            }
        }

        When("step 이 60 의 배수가 아니거나 60 미만이면") {
            Then("400 INVALID_REQUEST") {
                json(get("/timeline?$base&step=90"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
                get("/timeline?$base&step=30").statusCode.value() shouldBe 400
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("/timeline?service_name=tl-unknown&from=$t0&to=${t0.plusSeconds(300)}").statusCode.value() shouldBe 404
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("/timeline?service_name=tl-payment&from=${t0.plusSeconds(300)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get("/timeline?$base", bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

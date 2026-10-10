package com.monimo.api.query.error

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.query.support.NanoTime
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

// spans 에 실패 스팬을 넣고 GET /errors 를 실제 HTTP 로 부른다. ClickHouse · PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ErrorApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@errors.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "err-payment", null, null, now, now))
    applications.save(Application(UUID.randomUUID(), "err-gone", null, null, now, now, deletedAt = now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@errors.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/errors?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val t0 = Instant.ofEpochSecond(now.epochSecond - 600)

    fun span(
        id: String,
        offsetSec: Long,
        service: String = "err-payment",
        agent: String = "pod-1",
        kind: String = "SERVER",
        status: String = "ERROR",
        httpStatus: Int = 500,
        exception: Pair<String, String>? = null,
    ) {
        val at = t0.plusSeconds(offsetSec).toEpochMilli()
        val events = exception
            ?.let { (type, message) -> "[fromUnixTimestamp64Milli($at, 'UTC')], ['exception'], [map('exception.type', '$type', 'exception.message', '$message')]" }
            ?: "[], [], []"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind,
                               status_code, http_status, events.ts, events.name, events.attributes)
            VALUES ('trace-$id', '$id', '', fromUnixTimestamp64Milli($at, 'UTC'), 4000000, '$service', '$agent', 'POST /payments', '$kind',
                    '$status', $httpStatus, $events)
            """.trimIndent(),
        )
    }

    span("s1", 1, exception = "com.shop.TimeoutException" to "Read timed out")
    span("s2", 2, agent = "pod-2", httpStatus = 503, exception = "com.shop.GatewayException" to "bad gateway")
    span("s3", 3, kind = "CLIENT", httpStatus = 0)
    span("s4", 4, status = "UNSET", httpStatus = 200)
    span("s5", 5, agent = "pod-2", exception = "com.shop.TimeoutException" to "Read timed out")
    span("s5", 5, agent = "pod-2", exception = "com.shop.TimeoutException" to "Read timed out") // 같은 스팬이 두 번 적재된 경우
    span("s6", 6, service = "err-order")

    val base = "service_name=err-payment&from=$t0&to=${t0.plusSeconds(60)}"

    fun ids(response: ResponseEntity<String>) = json(response)["data"].map { it["span_id"].asText() }

    Given("화면이 에러 목록을 열 때") {
        When("서비스와 시간 범위만 주면") {
            val response = get(base)
            val data = json(response)["data"]

            Then("그 서비스의 실패한 스팬만 시간 역순으로 온다 (정상 스팬 · 다른 서비스 · 중복 적재 제외)") {
                response.statusCode.value() shouldBe 200
                ids(response) shouldBe listOf("s5", "s3", "s2", "s1")
                json(response)["page"]["next_cursor"].isNull shouldBe true
                json(response)["page"]["limit"].asInt() shouldBe 50
            }

            Then("예외 타입 · 메시지와 명세 필드가 실린다") {
                val s1 = data[3]
                s1["trace_id"].asText() shouldBe "trace-s1"
                s1["exception_type"].asText() shouldBe "com.shop.TimeoutException"
                s1["exception_message"].asText() shouldBe "Read timed out"
                s1["http_status"].asInt() shouldBe 500
                s1["agent_key"].asText() shouldBe "pod-1"
                s1["span_kind"].asText() shouldBe "SERVER"
                s1["status_code"].asText() shouldBe "ERROR"
                s1["duration_ns"].asLong() shouldBe 4_000_000
                s1["start_time"].asText() shouldBe NanoTime.format(t0.plusSeconds(1).toEpochMilli() * 1_000_000)
            }

            Then("예외 이벤트가 없고 HTTP 가 아닌 스팬은 세 값이 null") {
                val s3 = data[1]
                s3["span_kind"].asText() shouldBe "CLIENT"
                s3["http_status"].isNull shouldBe true
                s3["exception_type"].isNull shouldBe true
                s3["exception_message"].isNull shouldBe true
            }
        }

        When("필터를 주면") {
            Then("http_status 가 같은 것만") {
                ids(get("$base&http_status=500")) shouldBe listOf("s5", "s1")
            }

            Then("exception_type 이 같은 것만") {
                ids(get("$base&exception_type=com.shop.GatewayException")) shouldBe listOf("s2")
            }

            Then("agent_key 가 같은 것만") {
                ids(get("$base&agent_key=pod-1")) shouldBe listOf("s3", "s1")
            }
        }

        When("limit 2 로 나눠 읽으면") {
            val first = get("$base&limit=2")
            val cursor = json(first)["page"]["next_cursor"].asText()
            val second = get("$base&limit=2&cursor=$cursor")

            Then("첫 쪽은 2줄과 next_cursor, 다음 쪽은 이어지는 2줄과 null") {
                ids(first) shouldBe listOf("s5", "s3")
                ids(second) shouldBe listOf("s2", "s1")
                json(second)["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("cursor 가 깨져 있으면") {
            Then("400 INVALID_REQUEST") {
                json(get("$base&cursor=!!!"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("등록되지 않았거나 제외된 서비스면") {
            Then("404 NOT_FOUND") {
                val unknown = get("service_name=err-order&from=$t0&to=${t0.plusSeconds(60)}")
                unknown.statusCode.value() shouldBe 404
                json(unknown)["error"]["code"].asText() shouldBe "NOT_FOUND"
                get("service_name=err-gone&from=$t0&to=${t0.plusSeconds(60)}").statusCode.value() shouldBe 404
            }
        }

        When("service_name 이 빠지면") {
            Then("400 INVALID_REQUEST") {
                get("from=$t0&to=${t0.plusSeconds(60)}").statusCode.value() shouldBe 400
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("service_name=err-payment&from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(base, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

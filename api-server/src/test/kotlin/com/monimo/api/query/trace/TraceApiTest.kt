package com.monimo.api.query.trace

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
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

// spans 에 줄을 넣고 GET /traces/{traceId} 를 실제 HTTP 로 부른다. ClickHouse · PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class TraceApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@trace.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun login(): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@trace.io", "password" to "pw"))
        return json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    val token = login()

    fun get(traceId: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/traces/$traceId", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    val baseMs = now.minusSeconds(600).toEpochMilli()

    fun span(spanId: String, parent: String, service: String, offsetMs: Long, kind: String, httpStatus: Int, events: String = "[], [], []") {
        val status = if (httpStatus >= 500) "ERROR" else "UNSET"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind,
                               status_code, http_status, attributes, events.ts, events.name, events.attributes)
            VALUES ('$traceId', '$spanId', '$parent', fromUnixTimestamp64Milli(${baseMs + offsetMs}, 'UTC'), 5000000, '$service',
                    '$service-pod', 'op-$spanId', '$kind', '$status', $httpStatus, map('k', 'v-$spanId'), $events)
            """.trimIndent(),
        )
    }

    // gateway(500) → order(500) → payment(500, 예외 이벤트) · DB 호출(HTTP 아님)
    span("aaaaaaaaaaaaaaaa", "", "shop-gateway", 0, "SERVER", 500)
    span("bbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaa", "shop-order", 10, "SERVER", 500)
    span("dddddddddddddddd", "bbbbbbbbbbbbbbbb", "shop-order", 30, "CLIENT", 0)
    span(
        "cccccccccccccccc", "bbbbbbbbbbbbbbbb", "shop-payment", 20, "SERVER", 500,
        events = "[fromUnixTimestamp64Milli(${baseMs + 25}, 'UTC')], ['exception'], [map('exception.type', 'java.net.SocketTimeoutException')]",
    )
    // Kafka 재전송으로 같은 스팬이 두 번 들어온 경우
    span("dddddddddddddddd", "bbbbbbbbbbbbbbbb", "shop-order", 30, "CLIENT", 0)

    Given("화면이 트레이스 상세를 열 때") {
        When("있는 trace_id 로 부르면") {
            val response = get(traceId)
            val data = json(response)["data"]
            val root = data["root"]

            Then("200 에 루트 스팬부터 트리가 오고, 같은 스팬이 두 번 들어와도 한 번만 센다") {
                response.statusCode.value() shouldBe 200
                data["trace_id"].asText() shouldBe traceId
                data["span_count"].asInt() shouldBe 4
                data["services"].map { it.asText() } shouldBe listOf("shop-gateway", "shop-order", "shop-payment")
                root["span_id"].asText() shouldBe "aaaaaaaaaaaaaaaa"
                root["parent_span_id"].isNull shouldBe true
            }

            Then("자식은 시작 순서대로, 파드는 agent_key 로 나간다") {
                val order = root["children"][0]
                order["agent_key"].asText() shouldBe "shop-order-pod"
                order["children"].map { it["span_id"].asText() } shouldBe listOf("cccccccccccccccc", "dddddddddddddddd")
            }

            Then("HTTP 가 아닌 스팬의 http_status 는 0 이 아니라 null") {
                val db = root["children"][0]["children"][1]
                db["http_status"].isNull shouldBe true
                root["http_status"].asInt() shouldBe 500
            }

            Then("예외 이벤트와 꼬리표가 실린다") {
                val payment = root["children"][0]["children"][0]
                payment["events"][0]["name"].asText() shouldBe "exception"
                payment["events"][0]["attributes"]["exception.type"].asText() shouldBe "java.net.SocketTimeoutException"
                payment["attributes"]["k"].asText() shouldBe "v-cccccccccccccccc"
            }

            Then("시각은 나노초 9자리") {
                root["start_time"].asText() shouldBe SpanTree.format(baseMs * 1_000_000)
            }
        }

        When("대문자로 부르면") {
            Then("같은 트레이스가 온다") {
                json(get(traceId.uppercase()))["data"]["trace_id"].asText() shouldBe traceId
            }
        }

        When("없는 trace_id 면") {
            val response = get("0123456789abcdef0123456789abcdef")

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }

        When("trace_id 모양이 틀리면") {
            val response = get("not-a-trace-id")

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(traceId, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

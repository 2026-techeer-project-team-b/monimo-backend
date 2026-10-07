package com.monimo.api.query.transaction

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

// spans 에 SERVER 스팬을 넣으면 MV 가 transactions 를 채우고(서비스가 받은 요청마다 1줄, ADR #52), 그걸 GET /traces/scatter 로 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ScatterApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@scatter.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "sc-gateway", null, null, now, now))
    applications.save(Application(UUID.randomUUID(), "sc-order", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@scatter.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/traces/scatter?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val t0 = Instant.ofEpochSecond(now.epochSecond - 600)

    fun span(
        id: String, offsetSec: Long, durationMs: Long, httpStatus: Int, agent: String = "pod-a", parent: String = "",
        trace: String = id, service: String = "sc-gateway", kind: String = "SERVER",
    ) {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('sc-$trace', 's-$id', '$parent', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), ${durationMs * 1_000_000},
                    '$service', '$agent', 'POST /api/orders', '$kind', '${if (httpStatus >= 500) "ERROR" else "UNSET"}', $httpStatus)
            """.trimIndent(),
        )
    }

    // sc-gateway 가 받은 요청 6건: 빠른 성공 2 · 느린 성공 1 · 실패 2 · HTTP 아닌 요청 1
    span("r1", 1, 50, 200)
    span("r2", 2, 60, 200, agent = "pod-b")
    span("r3", 3, 2000, 200)
    span("r4", 4, 70, 500)
    span("r5", 5, 900, 500, agent = "pod-b")
    span("r6", 6, 10, 0)
    // r1 이 안에서 order 를 부른다. gateway 쪽의 CLIENT 스팬은 "보낸" 기록이라 요청이 아니고,
    // order 쪽의 SERVER 스팬은 order 가 "받은" 요청이라 sc-order 스캐터에 점으로 찍힌다 (ADR #52, 전에는 루트만 모아 0건이었다)
    span("r1-out", 7, 8, 200, parent = "s-r1", trace = "r1", kind = "CLIENT")
    span("r1-in", 7, 5, 200, parent = "s-r1-out", trace = "r1", service = "sc-order")

    val base = "service_name=sc-gateway&from=$t0&to=${t0.plusSeconds(60)}"

    fun ids(data: JsonNode) = data["points"].map { it["trace_id"].asText().removePrefix("sc-") }

    Given("화면이 스캐터를 그릴 때") {
        When("요청 수가 limit 이하면") {
            val response = get(base)
            val data = json(response)["data"]

            Then("mode raw 로 그 서비스가 받은 요청을 전부 시간 순으로 준다 (보낸 CLIENT 스팬은 요청이 아니다)") {
                response.statusCode.value() shouldBe 200
                data["mode"].asText() shouldBe "raw"
                data["total_count"].asLong() shouldBe 6
                ids(data) shouldBe listOf("r1", "r2", "r3", "r4", "r5", "r6")
            }

            Then("점마다 명세 필드가 실리고 is_error 는 true · false") {
                val r1 = data["points"][0]
                r1["start_time"].asText() shouldBe NanoTime.formatMillis(t0.plusSeconds(1).toEpochMilli())
                r1["duration_ms"].asLong() shouldBe 50
                r1["is_error"].isBoolean shouldBe true
                r1["is_error"].asBoolean() shouldBe false
                r1["http_status"].asInt() shouldBe 200
                r1["span_name"].asText() shouldBe "POST /api/orders"
                r1["agent_key"].asText() shouldBe "pod-a"
                data["points"][3]["is_error"].asBoolean() shouldBe true
            }

            Then("HTTP 가 아닌 요청의 http_status 는 null") {
                data["points"][5]["http_status"].isNull shouldBe true
            }
        }

        When("요청 수가 limit 을 넘으면") {
            val data = json(get("$base&limit=2"))["data"]

            Then("mode bucketed 로 접고, total_count 는 접기 전 요청 수다") {
                data["mode"].asText() shouldBe "bucketed"
                data["total_count"].asLong() shouldBe 6
            }

            Then("칸마다 가장 느린 실제 요청을 대표로 준다 (성공 · 실패는 따로) — 점 수는 limit 이하") {
                ids(data) shouldBe listOf("r3", "r5")
                data["points"][0]["duration_ms"].asLong() shouldBe 2000
                data["points"][1]["is_error"].asBoolean() shouldBe true
            }
        }

        When("요청을 처음 받은 서비스가 아니라 중간 서비스를 고르면") {
            val data = json(get("service_name=sc-order&from=$t0&to=${t0.plusSeconds(60)}"))["data"]

            Then("그 서비스가 받은 요청이 점으로 나온다. 루트가 아니어도 (#118)") {
                data["total_count"].asLong() shouldBe 1
                ids(data) shouldBe listOf("r1")
                data["points"][0]["duration_ms"].asLong() shouldBe 5
            }
        }

        When("agent_key 를 주면") {
            val data = json(get("$base&agent_key=pod-b"))["data"]

            Then("그 파드가 받은 요청만 세고 준다") {
                data["total_count"].asLong() shouldBe 2
                ids(data) shouldBe listOf("r2", "r5")
            }
        }

        When("요청이 없는 구간이면") {
            val data = json(get("service_name=sc-gateway&from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}"))["data"]

            Then("mode raw 에 빈 points") {
                data["mode"].asText() shouldBe "raw"
                data["total_count"].asLong() shouldBe 0
                data["points"].size() shouldBe 0
            }
        }

        When("limit 이 범위를 벗어나면") {
            Then("400 INVALID_REQUEST") {
                get("$base&limit=0").statusCode.value() shouldBe 400
                json(get("$base&limit=20001"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("service_name=sc-unknown&from=$t0&to=${t0.plusSeconds(60)}").statusCode.value() shouldBe 404
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("service_name=sc-gateway&from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(base, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

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

// spans 에 받은 요청(SERVER 스팬)을 넣으면 MV 가 transactions 를 채우고, 그걸 GET /traces/transactions 로 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class TransactionApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@tx.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "tx-order", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@tx.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/traces/transactions?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val t0 = Instant.ofEpochSecond(now.epochSecond - 600)

    fun span(id: String, offsetSec: Long, durationMs: Long, httpStatus: Int, agent: String = "pod-a", service: String = "tx-order", kind: String = "SERVER") {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('tx-$id', 's-$id', 'up-$id', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), ${durationMs * 1_000_000},
                    '$service', '$agent', 'POST /orders', '$kind', '${if (httpStatus >= 500) "ERROR" else "UNSET"}', $httpStatus)
            """.trimIndent(),
        )
    }

    // 받은 요청 5건. 부른 쪽(CLIENT) 스팬과 다른 서비스 요청은 목록에 없다
    span("a", 1, 50, 200)
    span("b", 2, 2000, 200)
    span("c", 3, 900, 500, agent = "pod-b")
    span("d", 4, 70, 500)
    span("e", 5, 300, 0, agent = "pod-b")
    span("client", 6, 5000, 200, kind = "CLIENT")
    span("other", 7, 5000, 200, service = "tx-payment")

    val base = "service_name=tx-order&from=$t0&to=${t0.plusSeconds(60)}"

    fun ids(response: ResponseEntity<String>) = json(response)["data"].map { it["trace_id"].asText().removePrefix("tx-") }

    Given("화면이 드래그한 영역의 요청 목록을 열 때") {
        When("서비스와 시간 범위만 주면") {
            val response = get(base)
            val data = json(response)["data"]

            Then("그 서비스가 받은 요청을 느린 순으로 준다") {
                response.statusCode.value() shouldBe 200
                ids(response) shouldBe listOf("b", "c", "e", "d", "a")
                json(response)["page"]["next_cursor"].isNull shouldBe true
            }

            Then("명세 필드가 실리고 is_error 는 true · false") {
                val c = data[1]
                c["start_time"].asText() shouldBe NanoTime.formatMillis(t0.plusSeconds(3).toEpochMilli())
                c["duration_ms"].asLong() shouldBe 900
                c["service_name"].asText() shouldBe "tx-order"
                c["agent_key"].asText() shouldBe "pod-b"
                c["span_name"].asText() shouldBe "POST /orders"
                c["is_error"].isBoolean shouldBe true
                c["is_error"].asBoolean() shouldBe true
                c["http_status"].asInt() shouldBe 500
                data[0]["is_error"].asBoolean() shouldBe false
            }

            Then("HTTP 가 아닌 요청의 http_status 는 null") {
                data[2]["http_status"].isNull shouldBe true
            }
        }

        When("응답시간 범위를 주면") {
            Then("min 이상") {
                ids(get("$base&min_duration_ms=300")) shouldBe listOf("b", "c", "e")
            }

            Then("max 이하") {
                ids(get("$base&max_duration_ms=70")) shouldBe listOf("d", "a")
            }

            Then("둘 다 주면 그 사이 (양 끝 포함)") {
                ids(get("$base&min_duration_ms=70&max_duration_ms=900")) shouldBe listOf("c", "e", "d")
            }
        }

        When("is_error 를 주면") {
            Then("true 는 실패만, false 는 성공만") {
                ids(get("$base&is_error=true")) shouldBe listOf("c", "d")
                ids(get("$base&is_error=false")) shouldBe listOf("b", "e", "a")
            }

            Then("명세 표기 1 · 0 도 받는다") {
                ids(get("$base&is_error=1")) shouldBe listOf("c", "d")
                ids(get("$base&is_error=0")) shouldBe listOf("b", "e", "a")
            }
        }

        When("agent_key 를 주면") {
            Then("그 파드가 받은 요청만") {
                ids(get("$base&agent_key=pod-b")) shouldBe listOf("c", "e")
            }
        }

        When("limit 2 로 나눠 읽으면") {
            val first = get("$base&limit=2")
            val second = get("$base&limit=2&cursor=${json(first)["page"]["next_cursor"].asText()}")
            val third = get("$base&limit=2&cursor=${json(second)["page"]["next_cursor"].asText()}")

            Then("세 쪽이 겹치지 않고 이어지고 마지막 쪽은 next_cursor 가 null") {
                ids(first) shouldBe listOf("b", "c")
                ids(second) shouldBe listOf("e", "d")
                ids(third) shouldBe listOf("a")
                json(third)["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("응답시간 범위가 틀리면") {
            Then("음수는 400 INVALID_REQUEST") {
                json(get("$base&min_duration_ms=-1"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }

            Then("min 이 max 보다 크면 422 UNPROCESSABLE") {
                val response = get("$base&min_duration_ms=900&max_duration_ms=100")
                response.statusCode.value() shouldBe 422
                json(response)["error"]["code"].asText() shouldBe "UNPROCESSABLE"
            }
        }

        When("cursor 가 깨져 있으면") {
            Then("400 INVALID_REQUEST") {
                get("$base&cursor=!!!").statusCode.value() shouldBe 400
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("service_name=tx-payment&from=$t0&to=${t0.plusSeconds(60)}").statusCode.value() shouldBe 404
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("service_name=tx-order&from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(base, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

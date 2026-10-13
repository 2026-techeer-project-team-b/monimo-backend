package com.monimo.api.query.log

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.query.support.NanoTime
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

// logs 에 줄을 넣고 GET /logs 를 실제 HTTP 로 부른다. ClickHouse · PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class LogApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@logs.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@logs.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/logs?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val t0 = Instant.ofEpochSecond(now.epochSecond - 600)

    fun log(
        message: String,
        offsetSec: Long,
        level: String,
        logger: String = "com.shop.OrderService",
        service: String = "lg-order",
        agent: String = "pod-a",
        traceId: String = "",
        spanId: String = "",
        attributes: String = "map()",
    ) {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO logs (trace_id, span_id, ts, service_name, agent_id, logger, thread, level, message, attributes)
            VALUES ('$traceId', '$spanId', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), '$service', '$agent',
                    '$logger', 'exec-1', '$level', '$message', $attributes)
            """.trimIndent(),
        )
    }

    // lg-order 5줄(그중 3줄은 같은 ms) + lg-payment 1줄. L1 · L2 · L6 은 같은 요청(lg-trace-1)의 로그다
    log("L1 order created", 1, "INFO", traceId = "lg-trace-1", spanId = "span-a", attributes = "map('order_id', '10293')")
    log("L2 payment gateway call failed: Read TIMED out", 2, "ERROR", logger = "com.shop.PaymentClient", traceId = "lg-trace-1", spanId = "span-b")
    log("L3 slow query", 3, "WARN", agent = "pod-b")
    log("L4 debug line", 3, "DEBUG", logger = "com.other.Util", agent = "pod-b")
    log("L5 same ms line", 3, "INFO", logger = "com.other.Util", agent = "pod-b")
    log("L6 pay failed", 4, "ERROR", logger = "com.pay.PayService", service = "lg-payment", agent = "pod-p", traceId = "lg-trace-1", spanId = "span-c")

    val range = "from=$t0&to=${t0.plusSeconds(60)}"
    val order = "$range&service_name=lg-order"

    // 메시지 앞 두 글자(L1 ~ L6)로 줄을 가리킨다
    fun ids(response: ResponseEntity<String>) = json(response)["data"].map { it["message"].asText().take(2) }

    Given("화면이 로그를 검색할 때") {
        When("서비스와 시간 범위를 주면") {
            val response = get(order)
            val data = json(response)["data"]

            Then("그 서비스 로그를 최신부터 준다 (같은 ms 세 줄이 맨 앞)") {
                response.statusCode.value() shouldBe 200
                ids(response).take(3) shouldContainExactlyInAnyOrder listOf("L3", "L4", "L5")
                ids(response).drop(3) shouldBe listOf("L2", "L1")
                json(response)["page"]["next_cursor"].isNull shouldBe true
            }

            Then("명세 필드가 실린다") {
                val l2 = data[3]
                l2["ts"].asText() shouldBe NanoTime.formatMillis(t0.plusSeconds(2).toEpochMilli())
                l2["service_name"].asText() shouldBe "lg-order"
                l2["agent_key"].asText() shouldBe "pod-a"
                l2["level"].asText() shouldBe "ERROR"
                l2["logger"].asText() shouldBe "com.shop.PaymentClient"
                l2["thread"].asText() shouldBe "exec-1"
                l2["trace_id"].asText() shouldBe "lg-trace-1"
                l2["span_id"].asText() shouldBe "span-b"
                data[4]["attributes"]["order_id"].asText() shouldBe "10293"
            }

            Then("요청과 이어지지 않은 로그의 trace_id · span_id 는 빈 글자") {
                val unlinked = data.first { it["message"].asText().startsWith("L3") }
                unlinked["trace_id"].asText() shouldBe ""
                unlinked["span_id"].asText() shouldBe ""
            }
        }

        When("service_name 없이 trace_id 만 주면") {
            Then("그 요청이 거친 모든 서비스의 로그를 준다 (트레이스 상세의 연결 로그)") {
                ids(get("$range&trace_id=lg-trace-1")) shouldBe listOf("L6", "L2", "L1")
            }
        }

        When("level 을 주면") {
            Then("쉼표로 여러 등급을 고른다 (대소문자 무관)") {
                ids(get("$order&level=ERROR,WARN")) shouldBe listOf("L3", "L2")
                ids(get("$order&level=error")) shouldBe listOf("L2")
            }

            Then("모르는 등급은 400 INVALID_REQUEST") {
                json(get("$order&level=LOUD"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("logger 를 주면") {
            Then("그 글자로 시작하는 로거만") {
                ids(get("$order&logger=com.shop")) shouldBe listOf("L3", "L2", "L1")
            }
        }

        When("agent_key 를 주면") {
            Then("그 파드 로그만") {
                ids(get("$order&agent_key=pod-a")) shouldBe listOf("L2", "L1")
            }
        }

        When("q 를 주면") {
            Then("본문에 그 글자가 든 로그만 (대소문자 무관)") {
                ids(get("$order&q=timed out")) shouldBe listOf("L2")
            }
        }

        When("limit 2 로 나눠 읽으면 (같은 ms 세 줄이 쪽 경계에 걸린다)") {
            val first = get("$order&limit=2")
            val second = get("$order&limit=2&cursor=${json(first)["page"]["next_cursor"].asText()}")
            val third = get("$order&limit=2&cursor=${json(second)["page"]["next_cursor"].asText()}")

            Then("빠지거나 겹치는 줄 없이 세 쪽으로 이어진다") {
                (ids(first) + ids(second) + ids(third)) shouldContainExactlyInAnyOrder listOf("L1", "L2", "L3", "L4", "L5")
                ids(first).size shouldBe 2
                ids(second).size shouldBe 2
                ids(third) shouldBe listOf("L1")
                json(third)["page"]["next_cursor"].isNull shouldBe true
            }

            Then("쪽을 나눠도 한 번에 읽은 순서와 같다") {
                (ids(first) + ids(second) + ids(third)) shouldBe ids(get(order))
            }
        }

        When("보관 기간(97일)보다 오래된 구간을 물으면") {
            val old = now.minus(Duration.ofDays(98))
            val response = get("from=$old&to=${old.plusSeconds(3600)}")

            Then("빈 목록이 아니라 404 SIGNAL_EXPIRED") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "SIGNAL_EXPIRED"
            }
        }

        When("cursor 가 깨져 있으면") {
            Then("400 INVALID_REQUEST") {
                get("$order&cursor=!!!").statusCode.value() shouldBe 400
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(order, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

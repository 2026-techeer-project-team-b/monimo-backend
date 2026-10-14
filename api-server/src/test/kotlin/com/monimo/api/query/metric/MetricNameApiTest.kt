package com.monimo.api.query.metric

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
import java.time.Duration
import java.time.Instant
import java.util.UUID

// metrics_raw 에 지표를 넣고 GET /metrics/names 로 이름과 속성 키 목록을 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class MetricNameApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@names.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "mn-order", null, null, now, now))
    applications.save(Application(UUID.randomUUID(), "mn-empty", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@names.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/metrics/names?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun metric(at: Instant, name: String, attributes: String = "map()", service: String = "mn-order", hash: Int = 1) {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
            VALUES ('$service', 'pod-a', '$name', $hash, $attributes, toDateTime(${at.epochSecond}, 'UTC'), 1)
            """.trimIndent(),
        )
    }

    // 최근 1시간: 힙(속성 조합 둘) · 스레드 수(속성 없음). 2시간 전: GC 시간. 다른 서비스: CPU
    val recent = now.minus(Duration.ofMinutes(10))
    val old = now.minus(Duration.ofHours(2))
    metric(recent, "jvm.memory.used", "map('jvm.memory.type', 'heap', 'jvm.memory.pool.name', 'G1 Eden Space')", hash = 1)
    metric(recent, "jvm.memory.used", "map('jvm.memory.type', 'non_heap', 'jvm.memory.pool.name', 'Metaspace')", hash = 2)
    metric(recent, "jvm.thread.count")
    metric(old, "jvm.gc.duration.sum", "map('jvm.gc.name', 'G1 Young Generation')")
    metric(recent, "jvm.cpu.recent_utilization", service = "mn-payment")

    fun names(response: ResponseEntity<String>) = json(response)["data"].map { it["metric_name"].asText() }

    Given("인스펙터가 지표 추가 드롭다운을 열 때") {
        When("서비스만 주면") {
            val response = get("service_name=mn-order")
            val data = json(response)["data"]

            Then("최근 1시간에 들어온 지표 이름을 글자 순으로 준다 (페이지 봉투 없음)") {
                response.statusCode.value() shouldBe 200
                names(response) shouldBe listOf("jvm.memory.used", "jvm.thread.count")
                json(response).has("page") shouldBe false
            }

            Then("지표마다 붙은 속성 키를 겹치지 않게 글자 순으로 준다") {
                data[0]["attribute_keys"].map { it.asText() } shouldBe listOf("jvm.memory.pool.name", "jvm.memory.type")
                data[1]["attribute_keys"].size() shouldBe 0
            }
        }

        When("from · to 를 주면") {
            Then("그 구간에 들어온 지표만 준다") {
                names(get("service_name=mn-order&from=${old.minusSeconds(60)}&to=${old.plusSeconds(60)}")) shouldBe listOf("jvm.gc.duration.sum")
            }

            Then("from 만 주면 to 는 지금이다") {
                names(get("service_name=mn-order&from=${old.minusSeconds(60)}")) shouldBe
                    listOf("jvm.gc.duration.sum", "jvm.memory.used", "jvm.thread.count")
            }
        }

        When("들어온 지표가 없는 서비스면") {
            val response = get("service_name=mn-empty")

            Then("404 가 아니라 200 에 빈 배열") {
                response.statusCode.value() shouldBe 200
                json(response)["data"].size() shouldBe 0
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("service_name=mn-payment").statusCode.value() shouldBe 404
            }
        }

        When("service_name 이 빠지면") {
            Then("400 INVALID_REQUEST") {
                get("from=$old").statusCode.value() shouldBe 400
            }
        }

        When("범위가 틀리면") {
            Then("from 이 to 보다 늦으면 422 UNPROCESSABLE") {
                get("service_name=mn-order&from=$now&to=$old").statusCode.value() shouldBe 422
            }

            Then("7일을 넘으면 422 TIME_RANGE_TOO_WIDE") {
                json(get("service_name=mn-order&from=${now.minus(Duration.ofDays(8))}&to=$now"))["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get("service_name=mn-order", bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

package com.monimo.api.query.agent

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// PG agents 에 파드를, ClickHouse metrics_raw 에 스레드 수를 넣고 GET /agents/{agentUuid}/active-threads 를 실제 HTTP 로 부른다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ActiveThreadsApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    postgres: JdbcTemplate,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@threads.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    val service = applications.save(Application(UUID.randomUUID(), "at-order", null, null, now, now))
    val gone = applications.save(Application(UUID.randomUUID(), "at-gone", null, null, now, now, deletedAt = now))

    fun pod(agentKey: String, applicationId: Long? = service.id): UUID {
        val uuid = UUID.randomUUID()
        postgres.update("INSERT INTO agents (agent_uuid, application_id, agent_key) VALUES (?, ?, ?)", uuid, applicationId, agentKey)
        return uuid
    }

    val live = pod("at-pod-live")
    val silent = pod("at-pod-silent")
    val stale = pod("at-pod-stale")
    val removed = pod("at-pod-removed", gone.id)

    val minute = now.truncatedTo(ChronoUnit.MINUTES)

    fun threads(agentKey: String, at: Instant, value: Int, hash: Int, daemon: Boolean, service: String = "at-order") {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
            VALUES ('$service', '$agentKey', 'jvm.thread.count', $hash, map('jvm.thread.daemon', '$daemon'), toDateTime(${at.epochSecond}, 'UTC'), $value)
            """.trimIndent(),
        )
    }

    // live: 5분 전(10 + 30) → 2분 전(11 → 12 로 바뀜, 33). 가장 최근 1분의 줄별 마지막 값을 더하면 12 + 33 = 45
    threads("at-pod-live", minute.minusSeconds(300), 10, hash = 1, daemon = true)
    threads("at-pod-live", minute.minusSeconds(300), 30, hash = 2, daemon = false)
    threads("at-pod-live", minute.minusSeconds(120), 11, hash = 1, daemon = true)
    threads("at-pod-live", minute.minusSeconds(105), 12, hash = 1, daemon = true)
    threads("at-pod-live", minute.minusSeconds(120), 33, hash = 2, daemon = false)
    // stale: 마지막 값이 2시간 전. removed: 제외된 서비스의 파드
    threads("at-pod-stale", minute.minusSeconds(7200), 20, hash = 1, daemon = true)
    threads("at-pod-removed", minute.minusSeconds(120), 20, hash = 1, daemon = true, service = "at-gone")

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@threads.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(agentUuid: Any, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/agents/$agentUuid/active-threads", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    Given("인스펙터가 파드의 현재 스레드 수를 물을 때") {
        When("최근에 값이 들어온 파드면") {
            val response = get(live)
            val data = json(response)["data"]

            Then("가장 최근 1분의 값을 주고, 속성별로 나뉜 줄을 더한다") {
                response.statusCode.value() shouldBe 200
                data["last_v"].asLong() shouldBe 45
                Instant.parse(data["ts_min"].asText()) shouldBe minute.minusSeconds(120)
            }

            Then("파드 · 서비스 · 지표 이름이 실린다") {
                data["agent_uuid"].asText() shouldBe live.toString()
                data["agent_key"].asText() shouldBe "at-pod-live"
                data["service_name"].asText() shouldBe "at-order"
                data["metric_name"].asText() shouldBe "jvm.thread.count"
            }
        }

        When("등록은 됐지만 값이 한 번도 안 들어온 파드면") {
            Then("404 NOT_FOUND") {
                val response = get(silent)
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }

        When("마지막 값이 1시간보다 오래된 파드면") {
            Then("404 NOT_FOUND") {
                get(stale).statusCode.value() shouldBe 404
            }
        }

        When("제외된 서비스의 파드면") {
            Then("404 NOT_FOUND (파드 목록 · 상세와 같은 규칙)") {
                get(removed).statusCode.value() shouldBe 404
            }
        }

        When("없는 파드면") {
            Then("404 NOT_FOUND") {
                get(UUID.randomUUID()).statusCode.value() shouldBe 404
            }
        }

        When("agentUuid 가 UUID 모양이 아니면") {
            Then("400 INVALID_REQUEST") {
                val response = get("not-a-uuid")
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(live, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

package com.monimo.api.config.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// 에이전트 3개 문과 서비스 상세의 agent_count. agents 는 수집 파트 표라 줄은 SQL 로 직접 넣는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class AgentApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    dataSource: DataSource,
) : BehaviorSpec({

    val jdbc = JdbcTemplate(dataSource)
    val now = Instant.parse("2026-10-03T09:00:00Z")
    users.save(User(UUID.randomUUID(), "agent-viewer@app.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    val order = applications.save(Application(UUID.randomUUID(), "agent-order", null, null, now, now))
    val pay = applications.save(Application(UUID.randomUUID(), "agent-pay", null, null, now, now))
    val gone = applications.save(Application(UUID.randomUUID(), "agent-gone", null, null, now, now, deletedAt = now))

    fun pod(app: Application, key: String, status: String = "UP", ip: String? = null): UUID {
        val uuid = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO agents (agent_uuid, application_id, agent_key, hostname, ip, status) VALUES (?, ?, ?, ?, ?::inet, ?)",
            uuid, app.id, key, key, ip, status,
        )
        return uuid
    }

    // agent_key 오름차순으로 나와야 한다. 일부러 뒤섞어 넣는다
    val orderPod2 = pod(order, "agent-order-pod-2", "DOWN")
    pod(order, "agent-order-pod-1", "UP", "10.244.3.17")
    pod(order, "agent-order-pod-3", "UP")
    pod(pay, "agent-pay-pod-1", "UP")
    pod(gone, "agent-gone-pod-1", "UP")

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun call(path: String, bearer: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(bearer)
        }
        return rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val viewer = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "agent-viewer@app.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun keys(path: String): List<String> = json(call(path, viewer))["data"].map { it["agent_key"].asText() }

    Given("파드 목록 (12번)") {
        When("필터 없이 부르면") {
            val data = json(call("/api/v1/agents?limit=500", viewer))["data"]
            val mine = data.filter { it["service_name"].asText().startsWith("agent-") }

            Then("agent_key 오름차순이고 제외된 서비스의 파드는 빠진다") {
                mine.map { it["agent_key"].asText() } shouldContainExactly listOf(
                    "agent-order-pod-1", "agent-order-pod-2", "agent-order-pod-3", "agent-pay-pod-1",
                )
            }

            Then("INET 컬럼이 주소 문자열로 나오고, 안 보낸 값은 null 이다") {
                val pod1 = mine.first { it["agent_key"].asText() == "agent-order-pod-1" }
                pod1["ip"].asText() shouldBe "10.244.3.17"
                pod1["jvm_version"].isNull shouldBe true
            }
        }

        When("service_name 과 status 로 거르면") {
            Then("둘 다 맞는 것만 나온다") {
                keys("/api/v1/agents?service_name=agent-order&status=UP&limit=500") shouldContainExactly
                    listOf("agent-order-pod-1", "agent-order-pod-3")
            }
        }

        When("limit 2 로 끊어 두 쪽을 읽으면") {
            val first = json(call("/api/v1/agents?service_name=agent-order&limit=2", viewer))
            val cursor = first["page"]["next_cursor"].asText()
            val second = json(call("/api/v1/agents?service_name=agent-order&limit=2&cursor=$cursor", viewer))

            Then("겹치지 않고 이어진다") {
                first["data"].map { it["agent_key"].asText() } shouldContainExactly listOf("agent-order-pod-1", "agent-order-pod-2")
                second["data"].map { it["agent_key"].asText() } shouldContainExactly listOf("agent-order-pod-3")
                second["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("status 가 UP · DOWN · UNKNOWN 이 아니면") {
            val response = call("/api/v1/agents?status=ZOMBIE", viewer)

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }
    }

    Given("파드 상세 (13번)") {
        When("있는 UUID 로 부르면") {
            val data = json(call("/api/v1/agents/$orderPod2", viewer))["data"]

            Then("그 한 줄이 나온다") {
                data["agent_key"].asText() shouldBe "agent-order-pod-2"
                data["status"].asText() shouldBe "DOWN"
                data["service_name"].asText() shouldBe "agent-order"
            }
        }

        When("없는 UUID 로 부르면") {
            val response = call("/api/v1/agents/${UUID.randomUUID()}", viewer)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }

    Given("서비스에 딸린 파드 (14번)") {
        When("그 서비스로 부르면") {
            Then("그 서비스 파드만 나온다") {
                keys("/api/v1/applications/${order.applicationUuid}/agents?limit=500") shouldContainExactly
                    listOf("agent-order-pod-1", "agent-order-pod-2", "agent-order-pod-3")
            }
        }

        When("제외된 서비스로 부르면") {
            val response = call("/api/v1/applications/${gone.applicationUuid}/agents", viewer)

            Then("빈 목록이 아니라 404 다") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }

    Given("서비스 상세의 agent_count (7번)") {
        When("파드가 3개인 서비스를 보면") {
            Then("0 이 아니라 3 이 나온다") {
                json(call("/api/v1/applications/${order.applicationUuid}", viewer))["data"]["agent_count"].asInt() shouldBe 3
            }
        }

        When("파드가 없는 서비스를 보면") {
            val empty = applications.save(Application(UUID.randomUUID(), "agent-empty", null, null, now, now))

            Then("0 이 나온다") {
                json(call("/api/v1/applications/${empty.applicationUuid}", viewer))["data"]["agent_count"].asInt() shouldBe 0
            }
        }
    }
})

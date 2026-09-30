package com.monimo.api.alert.rule

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.AlertChannelRepository
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// 규칙 7개 문을 실제 HTTP 로 돈다. 생성(연결 포함) → 목록 → 상세 → 수정(version) → 켜고 끄기 → 연결 조회 · 교체. PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class AlertRuleApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    channels: AlertChannelRepository,
    rules: AlertRuleRepository,
    links: AlertRuleChannelRepository,
    passwordEncoder: PasswordEncoder,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "admin@rule.io", passwordEncoder.encode("pw"), "관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "viewer@rule.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    val order = applications.save(Application(UUID.randomUUID(), "rule-order", null, null, now, now))
    val pay = applications.save(Application(UUID.randomUUID(), "rule-pay", null, null, now, now))
    val gone = applications.save(Application(UUID.randomUUID(), "rule-gone", null, null, now, now, deletedAt = now))

    fun channel(name: String) = channels.save(
        AlertChannel(UUID.randomUUID(), name, ChannelType.SLACK, mapOf("webhook_url" to "https://hooks.slack.com/services/T/B/$name"), true, now, now),
    )
    val backend = channel("backend")
    val oncall = channel("oncall")

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun call(method: HttpMethod, path: String, bearer: String, body: Any? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body?.let(objectMapper::writeValueAsString), headers), String::class.java)
    }

    fun login(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pw"))
        return json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    val admin = login("admin@rule.io")
    val viewer = login("viewer@rule.io")

    fun ruleBody(
        app: Application = order,
        name: String = "주문 5xx 급증",
        metricKind: String = "5XX_RATE",
        threshold: Any = 1,
        windowSec: Int = 300,
        severity: String = "CRITICAL",
        channelUuids: List<UUID> = listOf(backend.alertChannelUuid, oncall.alertChannelUuid),
    ) = mapOf(
        "application_uuid" to app.applicationUuid, "name" to name, "metric_kind" to metricKind, "operator" to "GT",
        "threshold" to threshold, "window_sec" to windowSec, "severity" to severity, "channel_uuids" to channelUuids,
    )

    fun create(body: Map<String, Any?>, token: String = admin) = call(HttpMethod.POST, "/api/v1/alert-rules", token, body)

    fun uuidOf(response: ResponseEntity<String>): String = json(response)["data"]["alert_rule_uuid"].asText()

    fun ruleOf(uuid: String) = rules.findActive(UUID.fromString(uuid)).shouldNotBeNull()

    Given("규칙 생성 (ADMIN)") {
        When("서비스와 채널 2개를 지정해 만들면") {
            val response = create(ruleBody())
            val data = json(response)["data"]

            Then("201 이고 서비스 이름 · 연결 채널(요청 순서)이 함께 나온다. config · 숫자 id 는 없다") {
                response.statusCode.value() shouldBe 201
                data["service_name"].asText() shouldBe "rule-order"
                data["metric_kind"].asText() shouldBe "5XX_RATE"
                data["threshold"].asDouble() shouldBe 1.0
                data["channels"].map { it["name"].asText() } shouldBe listOf("backend", "oncall")
                data["channels"][0].has("config") shouldBe false
                data.has("id") shouldBe false
            }

            Then("규칙은 version 1, 연결 2줄로 저장된다") {
                val rule = ruleOf(data["alert_rule_uuid"].asText())
                rule.version shouldBe 1
                links.channelsByRule(listOf(rule.id!!))[rule.id].orEmpty().size shouldBe 2
            }
        }

        When("잘못된 요청을 보내면") {
            val before = rules.count()
            val duplicate = create(ruleBody(channelUuids = listOf(backend.alertChannelUuid, backend.alertChannelUuid)))
            val missingChannel = create(ruleBody(channelUuids = listOf(backend.alertChannelUuid, UUID.randomUUID())))
            val excludedApp = create(ruleBody(app = gone))
            val badKind = create(ruleBody(metricKind = "5xx"))
            val badWindow = create(ruleBody(windowSec = 90))
            val badPercent = create(ruleBody(threshold = 150))
            val byViewer = create(ruleBody(), token = viewer)

            Then("중복 채널 409, 없는 채널 · 제외된 서비스 404, 값 모양 400, VIEWER 403") {
                duplicate.statusCode.value() shouldBe 409
                json(duplicate)["error"]["code"].asText() shouldBe "RULE_CHANNEL_DUPLICATE"
                missingChannel.statusCode.value() shouldBe 404
                excludedApp.statusCode.value() shouldBe 404
                listOf(badKind, badWindow, badPercent).forEach { it.statusCode.value() shouldBe 400 }
                byViewer.statusCode.value() shouldBe 403
            }

            Then("어느 경우에도 규칙이 생기지 않는다 (연결 실패 시 규칙까지 롤백)") {
                rules.count() shouldBe before
            }
        }
    }

    Given("규칙 목록 · 상세 (VIEWER+)") {
        create(ruleBody(app = pay, name = "결제 p95", metricKind = "P95_LATENCY", threshold = 800, severity = "WARNING"))
        create(ruleBody(app = pay, name = "결제 5xx"))

        When("VIEWER 가 service_name 으로 거르고 limit=1 로 넘기면") {
            val first = json(call(HttpMethod.GET, "/api/v1/alert-rules?service_name=rule-pay&limit=1", viewer))
            val second = json(call(HttpMethod.GET, "/api/v1/alert-rules?service_name=rule-pay&limit=1&cursor=${first["page"]["next_cursor"].asText()}", viewer))

            Then("그 서비스 규칙만 최신 순으로 오고, 목록에는 channels 가 없다") {
                first["data"].map { it["name"].asText() } shouldBe listOf("결제 5xx")
                second["data"].map { it["name"].asText() } shouldBe listOf("결제 p95")
                first["data"][0].has("channels") shouldBe false
            }
        }

        When("severity=WARNING 으로 거르면") {
            val names = json(call(HttpMethod.GET, "/api/v1/alert-rules?severity=WARNING&limit=500", viewer))["data"].map { it["name"].asText() }

            Then("WARNING 규칙만 온다") {
                names shouldBe listOf("결제 p95")
            }
        }

        When("VIEWER 가 상세를 보면") {
            val uuid = json(call(HttpMethod.GET, "/api/v1/alert-rules?service_name=rule-pay&limit=1", viewer))["data"][0]["alert_rule_uuid"].asText()
            val response = call(HttpMethod.GET, "/api/v1/alert-rules/$uuid", viewer)

            Then("200 이고 연결 채널이 함께 온다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["channels"].size() shouldBe 2
            }
        }

        When("없는 규칙을 보면") {
            val response = call(HttpMethod.GET, "/api/v1/alert-rules/${UUID.randomUUID()}", viewer)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
            }
        }
    }

    Given("규칙 수정 (ADMIN)") {
        val uuid = uuidOf(create(ruleBody(name = "수정용")))

        When("이름 · 심각도만 바꾸면") {
            call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid", admin, ruleBody(name = "수정됨", severity = "WARNING") - "application_uuid" - "channel_uuids")

            Then("version 은 그대로 1 이다 (평가 결과가 달라지지 않으니)") {
                val rule = ruleOf(uuid)
                rule.name shouldBe "수정됨"
                rule.severity shouldBe Severity.WARNING
                rule.version shouldBe 1
            }
        }

        When("threshold 를 바꾸면") {
            val response = call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid", admin, ruleBody(name = "수정됨", threshold = 2.5) - "application_uuid" - "channel_uuids")

            Then("200 이고 version 이 2 가 된다. 연결 채널은 그대로다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["threshold"].asDouble() shouldBe 2.5
                json(response)["data"]["channels"].size() shouldBe 2
                ruleOf(uuid).version shouldBe 2
            }
        }
    }

    Given("규칙 켜고 끄기 (ADMIN)") {
        val uuid = uuidOf(create(ruleBody(name = "토글용")))

        When("enabled=false 를 두 번 보내면") {
            val first = json(call(HttpMethod.PATCH, "/api/v1/alert-rules/$uuid/enabled", admin, mapOf("enabled" to false)))["data"]
            val second = json(call(HttpMethod.PATCH, "/api/v1/alert-rules/$uuid/enabled", admin, mapOf("enabled" to false)))["data"]

            Then("둘 다 꺼진 상태이고 updated_at 이 그대로다") {
                second["enabled"].asBoolean() shouldBe false
                second["updated_at"].asText() shouldBe first["updated_at"].asText()
            }
        }

        When("enabled 를 빠뜨리면") {
            val response = call(HttpMethod.PATCH, "/api/v1/alert-rules/$uuid/enabled", admin, emptyMap<String, Any>())

            Then("400 이고 꺼진 그대로다") {
                response.statusCode.value() shouldBe 400
                ruleOf(uuid).enabled shouldBe false
            }
        }
    }

    Given("규칙-채널 연결 조회 · 교체") {
        val uuid = uuidOf(create(ruleBody(name = "연결용")))
        fun connected() = json(call(HttpMethod.GET, "/api/v1/alert-rules/$uuid/channels", viewer))["data"]["channels"].map { it["name"].asText() }

        When("oncall 하나로 교체하면") {
            val response = call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid/channels", admin, mapOf("channel_uuids" to listOf(oncall.alertChannelUuid)))

            Then("200 이고 연결이 oncall 하나만 남는다") {
                response.statusCode.value() shouldBe 200
                connected() shouldBe listOf("oncall")
            }
        }

        When("중복이나 없는 채널로 교체하면") {
            val duplicate = call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid/channels", admin, mapOf("channel_uuids" to listOf(backend.alertChannelUuid, backend.alertChannelUuid)))
            val missing = call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid/channels", admin, mapOf("channel_uuids" to listOf(backend.alertChannelUuid, UUID.randomUUID())))

            Then("409 · 404 이고 연결은 바뀌지 않는다") {
                duplicate.statusCode.value() shouldBe 409
                missing.statusCode.value() shouldBe 404
                connected() shouldBe listOf("oncall")
            }
        }

        When("빈 배열로 교체하면") {
            call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid/channels", admin, mapOf("channel_uuids" to emptyList<UUID>()))

            Then("연결이 모두 풀린다") {
                connected() shouldBe emptyList()
            }
        }

        When("VIEWER 가 교체하면") {
            val response = call(HttpMethod.PUT, "/api/v1/alert-rules/$uuid/channels", viewer, mapOf("channel_uuids" to listOf(backend.alertChannelUuid)))

            Then("403 FORBIDDEN") {
                response.statusCode.value() shouldBe 403
            }
        }
    }
})

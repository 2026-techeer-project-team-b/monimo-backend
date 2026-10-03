package com.monimo.api.alert.event

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.AlertChannelRepository
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.alert.rule.AlertOperator
import com.monimo.api.alert.rule.AlertRule
import com.monimo.api.alert.rule.AlertRuleRepository
import com.monimo.api.alert.rule.Severity
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
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
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// 사건 3개 문을 실제 HTTP 로 돈다. 사건 · 이력은 탐지 · 알림이 쓰는 표라 테스트는 SQL 로 직접 넣는다. PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class AlertEventApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    channels: AlertChannelRepository,
    rules: AlertRuleRepository,
    passwordEncoder: PasswordEncoder,
    dataSource: DataSource,
) : BehaviorSpec({

    val jdbc = JdbcTemplate(dataSource)
    val now = Instant.parse("2026-10-01T09:00:00Z")
    users.save(User(UUID.randomUUID(), "viewer@event.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    val order = applications.save(Application(UUID.randomUUID(), "event-order", null, null, now, now))
    val pay = applications.save(Application(UUID.randomUUID(), "event-pay", null, null, now, now))
    val gone = applications.save(Application(UUID.randomUUID(), "event-gone", null, null, now, now, deletedAt = now))

    fun rule(app: Application, name: String) = rules.save(
        AlertRule(UUID.randomUUID(), app, name, "5XX_RATE", AlertOperator.GT, BigDecimal("5"), 60, Severity.CRITICAL, true, 1, now, now),
    )
    val orderRule = rule(order, "주문 5xx")
    val payRule = rule(pay, "결제 5xx")
    val goneRule = rule(gone, "제외된 서비스 규칙")

    val slack = channels.save(
        AlertChannel(UUID.randomUUID(), "백엔드-알람방", ChannelType.SLACK, mapOf("webhook_url" to "https://hooks.slack.com/services/T/B/x"), true, now, now),
    )

    // 파드 단위 사건용 파드 1개 (agents 는 수집 파트 표)
    val agentUuid = UUID.randomUUID()
    val agentId = jdbc.queryForObject(
        "INSERT INTO agents (agent_uuid, application_id, agent_key) VALUES (?, ?, 'event-order-pod-1') RETURNING id",
        Long::class.java, agentUuid, order.id,
    )!!

    // 발화 당시 스냅샷은 threshold 3 · WARNING 으로 넣는다. 지금 규칙(5 · CRITICAL)과 달라야 스냅샷을 읽는지 확인된다
    fun event(
        rule: AlertRule,
        firedAt: Instant,
        state: String = "FIRING",
        agent: Long? = null,
        severity: String = "WARNING",
    ): UUID {
        val uuid = UUID.randomUUID()
        val resolvedAt = if (state == "RESOLVED") Timestamp.from(firedAt.plusSeconds(300)) else null
        jdbc.update(
            """
            INSERT INTO alert_events (alert_event_uuid, alert_rule_id, agent_id, fingerprint, state, observed_value, fired_at, resolved_at,
                                      rule_version, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, ?, ?, ?, 28.9389, ?, ?, 1, '5XX_RATE', 'GT', 3, 60, ?)
            """.trimIndent(),
            uuid, rule.id, agent, uuid.toString().replace("-", ""), state, Timestamp.from(firedAt), resolvedAt, severity,
        )
        return uuid
    }

    fun eventId(uuid: UUID) = jdbc.queryForObject("SELECT id FROM alert_events WHERE alert_event_uuid = ?", Long::class.java, uuid)!!

    fun history(eventUuid: UUID, sentAt: Instant, result: String = "SUCCESS", retry: Int = 0): UUID {
        val uuid = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO notification_history (notification_uuid, alert_event_id, alert_channel_id, result, retry_count, response, sent_at) VALUES (?, ?, ?, ?, ?, 'ok', ?)",
            uuid, eventId(eventUuid), slack.id, result, retry, Timestamp.from(sentAt),
        )
        return uuid
    }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun login(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pw"))
        return json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    val viewer = login("viewer@event.io")

    fun get(path: String, bearer: String? = viewer): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let(::setBearerAuth) }
        return rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    val t0 = Instant.parse("2026-10-01T08:00:00Z")
    val orderOld = event(orderRule, t0)
    val orderPod = event(orderRule, t0.plusSeconds(60), agent = agentId)
    val orderResolved = event(orderRule, t0.plusSeconds(120), state = "RESOLVED")
    val payFiring = event(payRule, t0.plusSeconds(180), severity = "CRITICAL")
    event(goneRule, t0.plusSeconds(240))

    fun uuids(node: JsonNode) = node["data"].map { UUID.fromString(it["alert_event_uuid"].asText()) }

    Given("사건 목록 (#14)") {
        When("아무 조건 없이 보면") {
            val body = json(get("/api/v1/alert-events?limit=500"))

            Then("기본 state=FIRING 만, 최근 발화 순으로 온다. 제외된 서비스 사건은 없다") {
                uuids(body) shouldBe listOf(payFiring, orderPod, orderOld)
            }
        }

        When("state=RESOLVED 로 보면") {
            val body = json(get("/api/v1/alert-events?state=RESOLVED"))

            Then("해제된 사건만 오고 resolved_at 이 채워져 있다") {
                uuids(body) shouldBe listOf(orderResolved)
                body["data"][0]["resolved_at"].isNull shouldBe false
            }
        }

        When("service_name · severity 로 거르면") {
            val byService = uuids(json(get("/api/v1/alert-events?service_name=event-order")))
            val bySeverity = uuids(json(get("/api/v1/alert-events?severity=CRITICAL")))

            Then("조건에 맞는 사건만 온다. severity 는 지금 규칙이 아니라 발화 당시 값으로 거른다") {
                byService shouldBe listOf(orderPod, orderOld)
                bySeverity shouldBe listOf(payFiring)
            }
        }

        When("from · to 로 범위를 주면") {
            val body = json(get("/api/v1/alert-events?from=2026-10-01T08:01:00Z&to=2026-10-01T08:03:00Z"))

            Then("fired_at 이 [from, to) 안인 사건만 온다 (to 와 같은 시각은 빠진다)") {
                uuids(body) shouldBe listOf(orderPod)
            }
        }

        When("limit=1 로 넘기면") {
            val first = json(get("/api/v1/alert-events?limit=1"))
            val second = json(get("/api/v1/alert-events?limit=1&cursor=${first["page"]["next_cursor"].asText()}"))
            val third = json(get("/api/v1/alert-events?limit=1&cursor=${second["page"]["next_cursor"].asText()}"))

            Then("겹치거나 빠지는 사건 없이 차례로 오고, 마지막 쪽은 next_cursor 가 없다") {
                (uuids(first) + uuids(second) + uuids(third)) shouldBe listOf(payFiring, orderPod, orderOld)
                third["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("파드 단위 사건과 서비스 단위 사건이 섞여 있으면") {
            val rows = json(get("/api/v1/alert-events?service_name=event-order"))["data"]

            Then("파드 사건만 agent_uuid · agent_key 가 있고, 서비스 사건은 null 이다. 숫자 id 는 나가지 않는다") {
                rows[0]["agent_uuid"].asText() shouldBe agentUuid.toString()
                rows[0]["agent_key"].asText() shouldBe "event-order-pod-1"
                rows[1]["agent_uuid"].isNull shouldBe true
                rows[0].has("id") shouldBe false
            }
        }

        When("값이 잘못되면") {
            val onlyFrom = get("/api/v1/alert-events?from=2026-10-01T08:00:00Z")
            val reversed = get("/api/v1/alert-events?from=2026-10-01T09:00:00Z&to=2026-10-01T08:00:00Z")
            val tooWide = get("/api/v1/alert-events?from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z")
            val badState = get("/api/v1/alert-events?state=OPEN")
            val badCursor = get("/api/v1/alert-events?cursor=abc")
            val noToken = get("/api/v1/alert-events", bearer = null)

            Then("한쪽만 400, from≥to 422, 90일 초과 422, 모양 오류 400, 토큰 없음 401") {
                onlyFrom.statusCode.value() shouldBe 400
                reversed.statusCode.value() shouldBe 422
                json(tooWide)["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
                badState.statusCode.value() shouldBe 400
                badCursor.statusCode.value() shouldBe 400
                noToken.statusCode.value() shouldBe 401
            }
        }
    }

    Given("사건 상세 (#15)") {
        When("규칙을 고친 뒤에 지난 사건을 보면") {
            val data = json(get("/api/v1/alert-events/$orderOld"))["data"]

            Then("조건 · 심각도는 지금 규칙(5 · CRITICAL)이 아니라 발화 당시 스냅샷(3 · WARNING)이다") {
                data["rule_name"].asText() shouldBe "주문 5xx"
                data["service_name"].asText() shouldBe "event-order"
                data["threshold"].asDouble() shouldBe 3.0
                data["severity"].asText() shouldBe "WARNING"
                data["operator"].asText() shouldBe "GT"
                data["window_sec"].asInt() shouldBe 60
                data["metric_kind"].asText() shouldBe "5XX_RATE"
                data["observed_value"].asDouble() shouldBe 28.9389
            }
        }

        When("없는 사건 · 제외된 서비스의 사건을 보면") {
            val missing = get("/api/v1/alert-events/${UUID.randomUUID()}")
            val goneEvent = jdbc.queryForObject(
                "SELECT alert_event_uuid FROM alert_events WHERE alert_rule_id = ?", UUID::class.java, goneRule.id,
            )
            val excluded = get("/api/v1/alert-events/$goneEvent")

            Then("둘 다 404") {
                missing.statusCode.value() shouldBe 404
                excluded.statusCode.value() shouldBe 404
            }
        }
    }

    Given("전송 이력 (#16)") {
        val first = history(orderOld, t0.plusSeconds(1), result = "FAIL", retry = 7)
        val second = history(orderOld, t0.plusSeconds(400))

        When("이력이 있는 사건을 보면") {
            val rows = json(get("/api/v1/alert-events/$orderOld/notifications"))["data"]

            Then("최근 발송 순으로 채널 이름 · 종류 · 결과 · 재시도 수가 온다") {
                rows.map { it["notification_uuid"].asText() } shouldBe listOf(second.toString(), first.toString())
                rows[0]["channel_name"].asText() shouldBe "백엔드-알람방"
                rows[0]["type"].asText() shouldBe "SLACK"
                rows[0]["alert_channel_uuid"].asText() shouldBe slack.alertChannelUuid.toString()
                rows[1]["result"].asText() shouldBe "FAIL"
                rows[1]["retry_count"].asInt() shouldBe 7
            }
        }

        When("limit=1 로 넘기면") {
            val page1 = json(get("/api/v1/alert-events/$orderOld/notifications?limit=1"))
            val page2 = json(get("/api/v1/alert-events/$orderOld/notifications?limit=1&cursor=${page1["page"]["next_cursor"].asText()}"))

            Then("두 번째 쪽에 나머지 하나가 온다") {
                page2["data"].map { it["notification_uuid"].asText() } shouldBe listOf(first.toString())
                page2["page"]["next_cursor"].isNull shouldBe true
            }
        }

        When("다른 사건의 커서를 섞어 보내면") {
            val foreign = history(payFiring, t0.plusSeconds(500))
            val cursor = com.monimo.api.common.web.CursorCodec.encode(AlertEventService.NotificationCursor(foreign))
            val response = get("/api/v1/alert-events/$orderOld/notifications?cursor=$cursor")

            Then("400 (이 사건의 이력이 아니다)") {
                response.statusCode.value() shouldBe 400
            }
        }

        When("사건은 있는데 보낸 기록이 없거나, 사건이 없으면") {
            val empty = get("/api/v1/alert-events/$orderPod/notifications")
            val missing = get("/api/v1/alert-events/${UUID.randomUUID()}/notifications")

            Then("기록 없음은 200 빈 배열, 사건 없음은 404") {
                empty.statusCode.value() shouldBe 200
                json(empty)["data"].size() shouldBe 0
                missing.statusCode.value() shouldBe 404
            }
        }
    }
})

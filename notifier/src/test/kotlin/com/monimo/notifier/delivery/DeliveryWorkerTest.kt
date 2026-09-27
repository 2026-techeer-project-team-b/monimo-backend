package com.monimo.notifier.delivery

import com.monimo.notifier.channel.OutboundMessage
import com.monimo.notifier.channel.SlackWebhookSender
import com.monimo.notifier.support.ALERT_SCHEMA_FLYWAY
import com.monimo.notifier.support.FakeSlackServer
import com.monimo.notifier.support.MutableClock
import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val T0: Instant = Instant.parse("2026-09-27T00:00:00Z")

@TestConfiguration(proxyBeanMethods = false)
class DeliveryTestConfig {
    @Bean @Primary fun testClock() = MutableClock(T0)
    @Bean(destroyMethod = "close") fun fakeSlack() = FakeSlackServer()
}

// 실제 PostgreSQL + 가짜 Slack 수신 서버. 스케줄러는 끄고 pollOnce 를 직접 부른다
@SpringBootTest
@Import(TestInfraConfig::class, DeliveryTestConfig::class)
@TestPropertySource(
    properties = [
        ALERT_SCHEMA_FLYWAY,
        "monimo.notifier.delivery.enabled=false",
        "monimo.notifier.delivery.batch-size=10",
        "monimo.notifier.delivery.lease=30s",
        "monimo.notifier.delivery.max-attempts=3",
        "monimo.notifier.delivery.retry-base=5s",
        "monimo.notifier.delivery.retry-cap=60s",
        "monimo.notifier.http.connect-timeout=500ms",
        "monimo.notifier.http.request-timeout=500ms",
    ],
)
class DeliveryWorkerTest(
    worker: DeliveryWorker,
    claimer: OutboxClaimer,
    slackSender: SlackWebhookSender,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeSlackServer,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('order-service') RETURNING id", Long::class.java)!!
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, '주문 서비스 5xx 급증', '5XX_RATE', 'GT', 1, 300, 'CRITICAL') RETURNING id
        """,
        Long::class.java, appId,
    )!!

    fun channel(mode: String, enabled: Boolean = true, type: String = "SLACK"): Long = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES (?, ?, ?::jsonb, ?) RETURNING id",
        Long::class.java, "ch-$mode", type, """{"webhook_url":"${fake.url(mode)}"}""", enabled,
    )!!

    // 탐지가 만들었을 사건 1개 + 발송 작업 1개를 직접 넣는다 (모듈 경계상 탐지 코드를 쓰지 않는다)
    fun outbox(channelId: Long, ruleName: String = "rule"): Long {
        val now = Timestamp.from(clock.now)
        val eventId = jdbc.queryForObject(
            """
            INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at,
                rule_version, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, ?, 'FIRING', ?, 1, '5XX_RATE', 'GT', 1, 300, 'CRITICAL') RETURNING id
            """,
            // fingerprint 를 매번 다르게 해서 "진행 중 사건은 하나" 부분 UNIQUE 에 걸리지 않게 한다
            Long::class.java, UUID.randomUUID(), ruleId, UUID.randomUUID().toString().replace("-", ""), now,
        )!!
        return jdbc.queryForObject(
            """
            INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
            VALUES (?, 'FIRING', ?, ?::jsonb, 'PENDING', ?, ?, ?) RETURNING id
            """,
            Long::class.java, eventId, channelId,
            """{"transition":"FIRING","rule_name":"$ruleName","service_name":"order-service","severity":"CRITICAL"}""",
            now, now, now,
        )!!
    }

    fun row(id: Long): Map<String, Any?> = jdbc.queryForMap(
        "SELECT status, attempt_count, next_attempt_at, last_error, claim_token FROM notification_outbox WHERE id = ?", id,
    )
    fun historyOf(id: Long): List<Map<String, Any>> = jdbc.queryForList(
        """
        SELECT h.result, h.retry_count, h.response FROM notification_history h
        JOIN notification_outbox o ON o.alert_event_id = h.alert_event_id AND o.alert_channel_id = h.alert_channel_id
        WHERE o.id = ?
        """,
        id,
    )

    // 시나리오끼리 섞이지 않게: 남은 작업을 전부 끝난 상태로 돌리고 수신 기록을 비운다
    fun isolate() {
        jdbc.update("UPDATE notification_outbox SET status = 'CANCELLED', claim_token = NULL, lease_until = NULL WHERE status IN ('PENDING', 'IN_FLIGHT')")
        fake.reset()
    }

    Given("E4: 발송 작업 200개를 워커 4개가 동시에 처리하면") {
        isolate()
        val ok = channel("ok")
        val ids = (1..200).map { outbox(ok, "rule-$it") }

        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { pool.submit { while (worker.pollOnce() > 0) Unit } }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)

        Then("가짜 서버는 200개를 정확히 한 번씩 받고, 작업은 모두 SENT, 이력은 200줄") {
            fake.received.size shouldBe 200
            fake.received.toSet().size shouldBe 200
            ids.forEach { row(it)["status"] shouldBe "SENT" }
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_history WHERE result = 'SUCCESS' AND alert_channel_id = ?", Int::class.java, ok,
            ) shouldBe 200
        }
    }

    Given("E5: 워커 A 가 선점 후 처리를 멈춘다 — 모사: claim() 만 부르고 발송 · finish() 는 부르지 않음 (프로세스 종료 아님)") {
        isolate()
        val id = outbox(channel("ok"))
        val claimA = claimer.claim().single()

        When("임대(30초)가 끝나기 전 워커 B 가 폴링하면") {
            val took = worker.pollOnce()
            Then("IN_FLIGHT 작업을 건드리지 않는다") {
                took shouldBe 0
                fake.hits.get() shouldBe 0
            }
        }

        When("임대가 끝난 뒤 워커 B 가 폴링하면") {
            clock.advance(Duration.ofSeconds(31))
            worker.pollOnce()
            Then("B 가 회수해 보낸다") {
                fake.received.size shouldBe 1
                row(id)["status"] shouldBe "SENT"
            }
        }

        When("그 뒤 A 가 멈췄던 처리를 이어서 결과를 쓰려 하면") {
            val owned = claimer.finish(claimA, Finish(DeliveryStatus.FAILED, attempted = true, error = "A 의 늦은 결과"))
            Then("claim_token 이 달라 거부되고, B 의 SENT · 이력 1줄이 유지된다") {
                owned shouldBe false
                row(id)["status"] shouldBe "SENT"
                historyOf(id).size shouldBe 1
            }
        }
    }

    Given("E6: 발송 후 완료 기록 누락 재현 — 모사: A 가 sender 로 보낸 뒤 finish() 를 부르지 않음 (프로세스 종료 아님)") {
        isolate()
        val ok = channel("ok")
        val id = outbox(ok)
        val claimA = claimer.claim().single()
        slackSender.send(OutboundMessage(claimA.payload), mapOf("webhook_url" to fake.url("ok")))  // 접수됨. 완료 기록(finish)은 하지 않는다

        clock.advance(Duration.ofSeconds(31))
        worker.pollOnce()

        Then("임대 만료 뒤 B 가 다시 보내므로 같은 알림이 2번 도착한다 (Slack 은 멱등 키가 없어 막을 수 없다)") {
            fake.received.size shouldBe 2
            row(id)["status"] shouldBe "SENT"
            historyOf(id).size shouldBe 1  // DB 이력은 1줄 — 이력만 보면 중복을 알 수 없다
        }
    }

    Given("E7-a: 429 + Retry-After 120") {
        isolate()
        val id = outbox(channel("429"))
        worker.pollOnce()

        Then("최종 실패가 아니라 120초 뒤로 재예약한다 (워커는 기다리지 않는다)") {
            val r = row(id)
            r["status"] shouldBe "PENDING"
            r["attempt_count"] shouldBe 1
            (r["next_attempt_at"] as Timestamp).toInstant() shouldBeGreaterThanOrEqualTo clock.now.plusSeconds(120)
            (r["last_error"] as String) shouldContain "429"
            historyOf(id).size shouldBe 0
        }
    }

    Given("E7-b: 계속 503 (max-attempts=3)") {
        isolate()
        val id = outbox(channel("503"))
        repeat(5) {
            worker.pollOnce()
            clock.advance(Duration.ofMinutes(2))  // 백오프 상한(60초)보다 길게 → 매번 다시 꺼내진다
        }

        Then("실제 호출 3번 뒤 FAILED, 이력 FAIL 1줄 (retry_count 2)") {
            fake.hits.get() shouldBe 3
            row(id)["status"] shouldBe "FAILED"
            historyOf(id).single().let {
                it["result"] shouldBe "FAIL"
                it["retry_count"] shouldBe 2
            }
        }
    }

    Given("E7-c: 400 invalid_payload") {
        isolate()
        val id = outbox(channel("400"))
        worker.pollOnce()

        Then("다시 보내도 안 되므로 바로 FAILED (재시도 0)") {
            fake.hits.get() shouldBe 1
            row(id)["status"] shouldBe "FAILED"
            historyOf(id).single()["retry_count"] shouldBe 0
        }
    }

    Given("E7-d: 수신 서버가 받고 나서 응답을 2초 늦게 준다 (요청 시간 한도 0.5초)") {
        isolate()
        fake.slowMillis = 2_000
        val id = outbox(channel("slow"))
        worker.pollOnce()

        Then("결과를 모르는(Unknown) 호출로 보고 재예약한다 — 그런데 수신 서버는 이미 받았다") {
            row(id)["status"] shouldBe "PENDING"
            (row(id)["last_error"] as String) shouldContain "응답 시간 초과"
            fake.received.size shouldBe 1
        }
    }

    Given("발화 뒤 채널이 꺼졌으면") {
        isolate()
        val id = outbox(channel("ok", enabled = false))
        worker.pollOnce()

        Then("보내지 않고 CANCELLED. 외부 호출 0, 시도 수 0, 이력 없음") {
            fake.hits.get() shouldBe 0
            row(id)["status"] shouldBe "CANCELLED"
            row(id)["attempt_count"] shouldBe 0
            historyOf(id).size shouldBe 0
        }
    }

    Given("어댑터가 아직 없는 EMAIL 채널이면") {
        isolate()
        val id = outbox(channel("ok", type = "EMAIL"))
        worker.pollOnce()

        Then("성공으로 치지 않고 FAILED (미구현)") {
            row(id)["status"] shouldBe "FAILED"
            (row(id)["last_error"] as String) shouldContain "미구현"
        }
    }
})

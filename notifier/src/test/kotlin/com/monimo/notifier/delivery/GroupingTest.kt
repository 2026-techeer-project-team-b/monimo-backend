package com.monimo.notifier.delivery

import com.monimo.notifier.support.FakeSlackServer
import com.monimo.notifier.support.MutableClock
import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

private val G0: Instant = Instant.parse("2026-10-07T00:00:00Z")

@TestConfiguration(proxyBeanMethods = false)
class GroupingTestConfig {
    @Bean @Primary fun groupingClock() = MutableClock(G0)
    @Bean(destroyMethod = "close") fun groupingSlack() = FakeSlackServer()
}

// 그룹핑 (docs/alert/50-grouping.md). 같은 채널 · 서비스 알림을 group_wait(10초) 기다렸다 한 메시지로. 실제 PG + 가짜 Slack
@SpringBootTest
@Import(TestInfraConfig::class, GroupingTestConfig::class)
@TestPropertySource(
    properties = [
        "monimo.notifier.delivery.enabled=false",
        "monimo.notifier.delivery.group-wait=10s",
        "monimo.notifier.delivery.lease=30s",
        "monimo.notifier.delivery.retry-base=5s",
        "monimo.notifier.delivery.retry-cap=5s",
        "monimo.notifier.http.connect-timeout=500ms",
        "monimo.notifier.http.request-timeout=500ms",
    ],
)
class GroupingTest(
    worker: DeliveryWorker,
    claimer: OutboxClaimer,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeSlackServer,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('group-order') RETURNING id", Long::class.java)!!
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, 'g', '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
        """,
        Long::class.java, appId,
    )!!

    fun channel(mode: String): Long = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES (?, 'SLACK', ?::jsonb, true) RETURNING id",
        Long::class.java, "g-$mode-${UUID.randomUUID()}", """{"webhook_url":"${fake.url(mode)}"}""",
    )!!

    fun event(): Long = jdbc.queryForObject(
        """
        INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at,
            rule_version, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, ?, ?, 'FIRING', ?, 1, '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
        """,
        Long::class.java, UUID.randomUUID(), ruleId, UUID.randomUUID().toString().replace("-", ""), Timestamp.from(G0),
    )!!

    // 탐지가 넣었을 발송 작업 한 줄. 사건을 넘기면 그 사건의 줄(예: RESOLVED)을 만든다
    fun outbox(
        channelId: Long,
        rule: String,
        service: String = "group-order",
        createdAt: Instant = clock.now,
        severity: String = "CRITICAL",
        firedAt: String = "2026-10-07T00:00:00Z",
        transition: String = "FIRING",
        eventId: Long = event(),
    ): Pair<Long, Long> {
        val at = Timestamp.from(createdAt)
        val payload = """{"transition":"$transition","rule_name":"$rule","service_name":"$service","severity":"$severity",""" +
            """"metric_kind":"5XX_RATE","observed_value":9,"operator":"GT","threshold":5,"fired_at":"$firedAt","resolved_at":"2026-10-07T00:05:00Z"}"""
        val id = jdbc.queryForObject(
            """
            INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?) RETURNING id
            """,
            Long::class.java, eventId, transition, channelId, payload, at, at, at,
        )!!
        return id to eventId
    }

    fun status(id: Long) = jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String::class.java, id)
    fun historyCount(ids: List<Long>) = jdbc.queryForObject(
        "SELECT count(*) FROM notification_history h JOIN notification_outbox o ON o.alert_event_id = h.alert_event_id AND o.alert_channel_id = h.alert_channel_id WHERE o.id IN (${ids.joinToString()})",
        Int::class.java,
    )
    fun isolate() {
        jdbc.update("UPDATE notification_outbox SET status = 'CANCELLED', claim_token = NULL, lease_until = NULL WHERE status IN ('PENDING', 'IN_FLIGHT')")
        fake.reset()
    }
    fun at(sec: Long) { clock.now = G0.plusSeconds(sec) }

    Given("같은 서비스 · 같은 채널에서 규칙 3개가 같은 주기에 터진다") {
        isolate()
        at(0)
        val ch = channel("ok")
        val ids = listOf(
            outbox(ch, "주문 p95 지연", severity = "WARNING", firedAt = "2026-10-07T00:01:00Z"),
            outbox(ch, "주문 5xx 급증", firedAt = "2026-10-07T00:00:00Z"),
            outbox(ch, "주문 4xx 급증", severity = "WARNING", firedAt = "2026-10-07T00:00:00Z"),
        ).map { it.first }

        When("group_wait(10초) 전에 폴링하면") {
            at(5)
            val took = worker.pollOnce()
            Then("아직 보내지 않는다") {
                took shouldBe 0
                fake.hits.get() shouldBe 0
            }
        }

        When("10초가 지나 폴링하면") {
            at(11)
            worker.pollOnce()
            val text = fake.received.single()

            Then("Slack 은 1건 — 제목에 가장 높은 심각도와 건수, 줄은 발화 시각 순") {
                text shouldContain "[FIRING][CRITICAL] group-order 경보 3건"
                text shouldContain "• 주문 5xx 급증 — 5XX_RATE 9 GT 5"
                (text.indexOf("주문 4xx 급증") < text.indexOf("주문 5xx 급증")) shouldBe true  // 같은 시각이면 이름 순
                (text.indexOf("주문 5xx 급증") < text.indexOf("주문 p95 지연")) shouldBe true  // 먼저 터진 것이 위
            }

            Then("작업 3줄 모두 SENT, 이력도 3줄 (사건은 따로 남는다)") {
                ids.forEach { status(it) shouldBe "SENT" }
                historyCount(ids) shouldBe 3
            }
        }
    }

    Given("첫 알림을 기다리는 동안 형제가 늦게 들어온다") {
        isolate()
        at(100)
        val ch = channel("ok")
        val first = outbox(ch, "먼저").first
        at(108)
        val late = outbox(ch, "늦게").first

        When("첫 줄의 10초가 지나 폴링하면 (늦은 줄은 아직 2초)") {
            at(111)
            worker.pollOnce()

            Then("늦게 들어온 형제도 같이 한 메시지로 나간다") {
                fake.received.size shouldBe 1
                fake.received.single() shouldContain "경보 2건"
                status(first) shouldBe "SENT"
                status(late) shouldBe "SENT"
            }
        }
    }

    Given("다른 서비스 둘이 같은 채널로 동시에 터진다") {
        isolate()
        at(200)
        val ch = channel("ok")
        outbox(ch, "주문", service = "group-order")
        outbox(ch, "결제", service = "group-pay")
        at(211)
        worker.pollOnce()

        Then("서비스마다 따로 — Slack 2건, 각각 묶음 표시 없는 한 건짜리 문구") {
            fake.received.size shouldBe 2
            fake.received.forEach { it shouldNotContain "건\\n" }
        }
    }

    Given("E9 모사: 그룹을 잡은 알림 서비스가 보내기 전에 멈춘다 (claim 만 하고 finish 안 함 — 프로세스 종료 아님)") {
        isolate()
        at(300)
        val ch = channel("ok")
        val a = outbox(ch, "a").first
        val b = outbox(ch, "b").first
        at(311)
        val stuck = claimer.claim()
        at(320)
        val c = outbox(ch, "c").first  // 멈춰 있는 동안 형제가 하나 더 생김

        When("임대(30초)가 끝난 뒤 다른 워커가 폴링하면") {
            at(345)
            worker.pollOnce()

            Then("멈췄던 2줄과 새 형제가 빠짐없이 한 메시지로 나간다") {
                stuck.size shouldBe 2
                fake.received.size shouldBe 1
                fake.received.single() shouldContain "경보 3건"
                listOf(a, b, c).forEach { status(it) shouldBe "SENT" }
            }
        }
    }

    Given("E10: 발화 알림이 재시도 대기 중일 때 복구가 생긴다") {
        isolate()
        fake.switchStatus = 503
        at(400)
        val ch = channel("switch")
        val (firing, eventId) = outbox(ch, "주문 5xx")
        at(411)
        worker.pollOnce()  // 503 → FIRING 은 PENDING 으로 재시도 대기 (시도 1회)
        val resolved = outbox(ch, "주문 5xx", transition = "RESOLVED", eventId = eventId, createdAt = clock.now).first

        When("복구 줄의 10초가 지났지만 발화는 아직 재시도 시각 전") {
            at(422)
            fake.switchStatus = 200
            fake.reset()
            jdbc.update("UPDATE notification_outbox SET next_attempt_at = ? WHERE id = ?", Timestamp.from(G0.plusSeconds(500)), firing)
            worker.pollOnce()

            Then("복구를 먼저 보내지 않는다") {
                fake.hits.get() shouldBe 0
                status(resolved) shouldBe "PENDING"
            }
        }

        When("발화의 재시도 시각이 되면") {
            at(501)
            worker.pollOnce()
            worker.pollOnce()

            Then("발화가 먼저, 복구가 그다음에 간다") {
                status(firing) shouldBe "SENT"
                status(resolved) shouldBe "SENT"
                val order = fake.received.toList()
                order.size shouldBe 2
                order[0] shouldContain "[FIRING]"
                order[1] shouldContain "[RESOLVED]"
            }
        }
    }

    Given("발송 전 복구: 발화를 한 번도 보내지 않았는데 복구가 생긴다") {
        isolate()
        at(600)
        val ch = channel("ok")
        val (firing, eventId) = outbox(ch, "잠깐 튐")
        val resolved = outbox(ch, "잠깐 튐", transition = "RESOLVED", eventId = eventId).first
        at(611)
        worker.pollOnce()

        Then("둘 다 CANCELLED, Slack 0건 — 터졌다 · 풀렸다를 연달아 보내지 않는다") {
            status(firing) shouldBe "CANCELLED"
            status(resolved) shouldBe "CANCELLED"
            fake.hits.get() shouldBe 0
        }
    }

    Given("묶음이 503 으로 실패한다") {
        isolate()
        fake.switchStatus = 503
        at(700)
        val ch = channel("switch")
        val ids = listOf(outbox(ch, "x").first, outbox(ch, "y").first)
        at(711)
        worker.pollOnce()

        Then("Slack 호출은 1번, 두 줄 모두 같은 재시도 시각 · 시도 1회로 남아 다음에도 같이 나간다") {
            fake.hits.get() shouldBe 1
            val rows = jdbc.queryForList(
                "SELECT status, attempt_count, next_attempt_at FROM notification_outbox WHERE id IN (${ids.joinToString()})",
            )
            rows.map { it["status"] } shouldBe listOf("PENDING", "PENDING")
            rows.map { it["attempt_count"] } shouldBe listOf(1, 1)
            rows.map { it["next_attempt_at"] }.toSet().size shouldBe 1
        }
        fake.switchStatus = 200
    }

    Given("D18-보강: 발화 알림이 영구 실패(400)로 끝난 뒤 복구가 생긴다") {
        isolate()
        fake.switchStatus = 400
        at(800)
        val ch = channel("switch")
        val (firing, eventId) = outbox(ch, "결제 실패율")
        at(811)
        worker.pollOnce()  // 400 → Permanent → FIRING FAILED (사람은 발화를 못 받음)
        fake.switchStatus = 200
        fake.reset()
        val resolved = outbox(ch, "결제 실패율", transition = "RESOLVED", eventId = eventId, createdAt = clock.now).first
        at(822)
        worker.pollOnce()

        Then("복구는 숨기지 않고 보내되, 발화 알림이 전달되지 못했다고 적는다") {
            status(firing) shouldBe "FAILED"
            status(resolved) shouldBe "SENT"
            fake.received.single() shouldContain "[RESOLVED] 결제 실패율"
            fake.received.single() shouldContain "발화 알림은 전달되지 못했습니다"
        }
    }

    Given("D18-보강: 발화가 정상 발송된 사건의 복구") {
        isolate()
        at(900)
        val ch = channel("ok")
        val (_, eventId) = outbox(ch, "정상 사건")
        at(911)
        worker.pollOnce()
        fake.reset()
        outbox(ch, "정상 사건", transition = "RESOLVED", eventId = eventId, createdAt = clock.now)
        at(922)
        worker.pollOnce()

        Then("표시 없이 평소 복구 문구") {
            fake.received.single() shouldNotContain "전달되지 못했습니다"
        }
    }
})

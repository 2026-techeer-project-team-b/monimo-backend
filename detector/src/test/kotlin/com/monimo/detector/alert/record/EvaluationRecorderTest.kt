package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.AlertOperator
import com.monimo.detector.alert.state.AlertTarget
import com.monimo.detector.alert.state.Evaluation
import com.monimo.detector.alert.state.EvaluationPhase
import com.monimo.detector.alert.state.IgnoreReason
import com.monimo.detector.alert.state.Outcome
import com.monimo.detector.alert.state.Transition
import com.monimo.detector.alert.state.Verdict
import com.monimo.detector.support.ALERT_SCHEMA_FLYWAY
import com.monimo.detector.support.MutableClock
import com.monimo.detector.support.TestInfraConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val T0: Instant = Instant.parse("2026-09-27T00:00:00Z")
private fun bucket(minute: Long): Instant = T0.plus(Duration.ofMinutes(minute))

// "사건 저장 뒤 · outbox 저장 전"에 실패를 끼워 넣는 스위치
class SwitchableHook : TransitionHook {
    @Volatile var failNext = false
    override fun afterEventSaved(alertEventId: Long) {
        if (failNext) {
            failNext = false
            throw IllegalStateException("주입한 장애: 사건 $alertEventId 저장 뒤 outbox 저장 전")
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RecorderTestConfig {
    @Bean @Primary fun testClock() = MutableClock(T0)
    @Bean @Primary fun testTransitionHook() = SwitchableHook()
}

// 실제 PostgreSQL(Testcontainers)에서 상태 · 사건 · outbox 가 한 트랜잭션으로 움직이는지 본다. N=3, M=2 (기본 설정값)
@SpringBootTest
@Import(TestInfraConfig::class, RecorderTestConfig::class)
@TestPropertySource(properties = [ALERT_SCHEMA_FLYWAY])
class EvaluationRecorderTest(
    recorder: EvaluationRecorder,
    jdbc: JdbcTemplate,
    hook: SwitchableHook,
) : BehaviorSpec({

    val appId = jdbc.queryForObject(
        "INSERT INTO applications (name) VALUES ('order-service') RETURNING id", Long::class.java,
    )!!

    fun channel(name: String, enabled: Boolean): Long = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES (?, 'SLACK', '{}'::jsonb, ?) RETURNING id",
        Long::class.java, name, enabled,
    )!!

    // 규칙 하나 + 연결 채널. 시나리오마다 새 규칙을 만들어 fingerprint 가 겹치지 않게 한다
    fun newTarget(vararg channelIds: Long): EvaluationTarget {
        val uuid = UUID.randomUUID()
        val ruleId = jdbc.queryForObject(
            """
            INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, '주문 서비스 5xx 급증', '5XX_RATE', 'GT', 1, 300, 'CRITICAL') RETURNING id
            """,
            Long::class.java, uuid, appId,
        )!!
        channelIds.forEach { jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", ruleId, it) }
        val rule = RuleSnapshot(ruleId, uuid, 1, "주문 서비스 5xx 급증", "order-service", "5XX_RATE", AlertOperator.GT, BigDecimal.ONE, 300, "CRITICAL")
        return EvaluationTarget(rule, AlertTarget.Service(UUID.randomUUID()), agentId = null)
    }

    fun violating(minute: Long) = Evaluation(1, Verdict.Violating(bucket(minute), BigDecimal("3.7400")))
    fun ok(minute: Long) = Evaluation(1, Verdict.Ok(bucket(minute), BigDecimal("0.2000")))

    fun eventCount(fp: String) = jdbc.queryForObject("SELECT count(*) FROM alert_events WHERE fingerprint = ?", Int::class.java, fp)!!
    fun outboxRows(fp: String): List<Pair<String, Long>> = jdbc.query(
        """
        SELECT o.transition, o.alert_channel_id FROM notification_outbox o
        JOIN alert_events e ON e.id = o.alert_event_id WHERE e.fingerprint = ? ORDER BY o.id
        """,
        { rs, _ -> rs.getString(1) to rs.getLong(2) }, fp,
    )

    Given("켜진 채널 2개 + 꺼진 채널 1개가 연결된 규칙") {
        val a = channel("백엔드-알람방", true)
        val b = channel("온콜-알람방", true)
        val off = channel("꺼진-알람방", false)
        val target = newTarget(a, b, off)
        val fp = target.fingerprint

        When("위반 3번") {
            (1L..3L).forEach { recorder.record(target, violating(it)) }

            Then("FIRING 사건 1개와 켜진 채널 2개의 FIRING 발송 의도가 생긴다") {
                eventCount(fp) shouldBe 1
                outboxRows(fp) shouldContainExactlyInAnyOrder listOf("FIRING" to a, "FIRING" to b)
            }
            Then("사건에 발화 당시 규칙 조건이 복사된다") {
                jdbc.queryForMap("SELECT state, threshold, operator, rule_version, observed_value FROM alert_events WHERE fingerprint = ?", fp).let {
                    it["state"] shouldBe "FIRING"
                    (it["threshold"] as BigDecimal).compareTo(BigDecimal.ONE) shouldBe 0
                    it["operator"] shouldBe "GT"
                    it["rule_version"] shouldBe 1
                }
            }
        }

        When("이어서 정상 2번") {
            (4L..5L).forEach { recorder.record(target, ok(it)) }

            Then("같은 사건이 RESOLVED 가 되고, 발화를 받은 채널로 RESOLVED 발송 의도가 생긴다") {
                eventCount(fp) shouldBe 1
                jdbc.queryForObject("SELECT state FROM alert_events WHERE fingerprint = ?", String::class.java, fp) shouldBe "RESOLVED"
                outboxRows(fp).filter { it.first == "RESOLVED" } shouldContainExactlyInAnyOrder listOf("RESOLVED" to a, "RESOLVED" to b)
            }
        }

        When("복구 뒤 다시 위반 3번") {
            (6L..8L).forEach { recorder.record(target, violating(it)) }

            Then("같은 fingerprint 에 새 사건이 생긴다 (부분 UNIQUE 가 재발을 막지 않는다)") {
                eventCount(fp) shouldBe 2
                jdbc.queryForObject("SELECT count(DISTINCT alert_event_uuid) FROM alert_events WHERE fingerprint = ?", Int::class.java, fp) shouldBe 2
            }
        }
    }

    Given("E3: 사건 저장 뒤 outbox 저장 전에 장애가 나면") {
        val a = channel("e3-a", true)
        val b = channel("e3-b", true)
        val target = newTarget(a, b)
        val fp = target.fingerprint
        recorder.record(target, violating(1))
        recorder.record(target, violating(2))

        hook.failNext = true
        val error = shouldThrow<IllegalStateException> { recorder.record(target, violating(3)) }

        Then("사건 · outbox · 평가 상태가 전부 롤백된다 (사건만 남고 알림이 사라지는 일이 없다)") {
            error.message!! shouldContain "주입한 장애"
            eventCount(fp) shouldBe 0
            outboxRows(fp) shouldBe emptyList()
            jdbc.queryForMap("SELECT phase, consecutive_bad, last_bucket_end FROM alert_evaluation_states WHERE fingerprint = ?", fp).let {
                it["phase"] shouldBe "PENDING"
                it["consecutive_bad"] shouldBe 2
            }
        }

        When("같은 버킷을 다시 평가하면") {
            val outcome = recorder.record(target, violating(3))

            Then("롤백됐으므로 이번에는 정상적으로 발화한다") {
                (outcome as Outcome.Applied).transition.shouldBeInstanceOf<Transition.Fired>()
                eventCount(fp) shouldBe 1
                outboxRows(fp) shouldContainExactlyInAnyOrder listOf("FIRING" to a, "FIRING" to b)
            }
        }
    }

    Given("E2: 탐지 2개가 같은 fingerprint 의 같은 버킷을 동시에 평가하면 (20회 반복)") {
        val a = channel("e2-a", true)
        val pool = Executors.newFixedThreadPool(2)

        val results = (1..20).map {
            val target = newTarget(a)
            recorder.record(target, violating(1))
            recorder.record(target, violating(2))
            // 둘 다 bad=2 를 보고 들어가는 상황을 만든다. 잠금이 없으면 둘 다 3번째 위반으로 발화한다
            val barrier = CyclicBarrier(2)
            val outcomes = List(2) { pool.submit<Outcome> { barrier.await(); recorder.record(target, violating(3)) } }
                .map { it.get(30, TimeUnit.SECONDS) }
            target.fingerprint to outcomes
        }
        pool.shutdown()

        Then("매번 사건 1개 · 발송 의도 1개이고, 늦은 쪽은 DUPLICATE_BUCKET 으로 무시된다") {
            results.forEach { (fp, outcomes) ->
                eventCount(fp) shouldBe 1
                outboxRows(fp).size shouldBe 1
                outcomes.count { it is Outcome.Applied && it.transition is Transition.Fired } shouldBe 1
                outcomes.count { it is Outcome.Ignored && it.reason == IgnoreReason.DUPLICATE_BUCKET } shouldBe 1
            }
        }
    }

    Given("처음 보는 fingerprint 를 두 탐지가 동시에 평가하면 (평가 상태 줄 생성 경쟁, 20회 반복)") {
        val pool = Executors.newFixedThreadPool(2)
        val fps = (1..20).map {
            val target = newTarget()
            val barrier = CyclicBarrier(2)
            List(2) { pool.submit<Outcome> { barrier.await(); recorder.record(target, violating(1)) } }.forEach { it.get(30, TimeUnit.SECONDS) }
            target.fingerprint
        }
        pool.shutdown()

        Then("UNIQUE 충돌 없이 줄 1개, 위반은 1번만 센다") {
            fps.forEach { fp ->
                jdbc.queryForMap("SELECT count(*) AS n, max(consecutive_bad) AS bad FROM alert_evaluation_states WHERE fingerprint = ?", fp).let {
                    it["n"] shouldBe 1L
                    it["bad"] shouldBe 1
                }
            }
        }
    }

    Given("최종 방어선: 같은 fingerprint 에 FIRING 사건을 직접 하나 더 넣으면") {
        val target = newTarget()
        (1L..3L).forEach { recorder.record(target, violating(it)) }
        val ruleId = target.rule.id

        Then("부분 UNIQUE 인덱스가 거부한다") {
            shouldThrow<DuplicateKeyException> {
                jdbc.update(
                    """
                    INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at,
                        rule_version, metric_kind, operator, threshold, window_sec, severity)
                    VALUES (?, ?, ?, 'FIRING', now(), 1, '5XX_RATE', 'GT', 1, 300, 'CRITICAL')
                    """,
                    UUID.randomUUID(), ruleId, target.fingerprint,
                )
            }
            jdbc.queryForObject("SELECT phase FROM alert_evaluation_states WHERE fingerprint = ?", String::class.java, target.fingerprint) shouldBe
                EvaluationPhase.FIRING.name
        }
    }
})

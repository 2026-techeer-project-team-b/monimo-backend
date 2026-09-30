package com.monimo.detector.alert.evaluate

import com.monimo.detector.support.MutableClock
import com.monimo.detector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val START: Instant = Instant.parse("2026-09-30T10:00:00Z")

// 조회 파트 API 대신 쓰는 가짜. 분마다 5xx 비율을 정해 두면 그 값으로 1분 버킷을 만든다
class FakeServiceHealth : ServiceHealthClient {
    @Volatile var fail = false
    val percentByMinute = mutableMapOf<Instant, Long>()  // 버킷 시작 → 5xx % (100건 중)

    override fun fetch(serviceName: String, from: Instant, to: Instant): List<HealthBucket> {
        if (fail) throw ServiceHealthQueryException("주입한 장애: service-health 500")
        return percentByMinute.filterKeys { !it.isBefore(from) && it.isBefore(to) }.toSortedMap()
            .map { (start, pct) -> HealthBucket(start, 100, 0, pct, 100) }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RunnerTestConfig {
    @Bean @Primary fun testClock() = MutableClock(START)
    @Bean @Primary fun fakeServiceHealth() = FakeServiceHealth()
}

// 실제 PG + 가짜 조회. "5xx 가 3분 이어지면 사건 1건 + 켜진 채널에 발송 의도" 가 끝까지 도는지 본다. N=3 · M=2
@SpringBootTest
@Import(TestInfraConfig::class, RunnerTestConfig::class)
class EvaluationRunnerTest(
    runner: EvaluationRunner,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeServiceHealth,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('runner-order') RETURNING id", Long::class.java)!!
    val onId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('켜짐', 'SLACK', '{}'::jsonb, true) RETURNING id", Long::class.java,
    )!!
    val offId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('꺼짐', 'SLACK', '{}'::jsonb, false) RETURNING id", Long::class.java,
    )!!
    // 5xx 비율 > 1% (window 60초 = 판정 버킷 하나)
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, ?, '주문 5xx', '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
        """,
        Long::class.java, UUID.randomUUID(), appId,
    )!!
    listOf(onId, offId).forEach { jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", ruleId, it) }

    fun minute(m: Long): Instant = START.plus(Duration.ofMinutes(m))
    // m 분 버킷이 끝나고 settle(30초)이 지난 시각으로 시계를 옮긴 뒤 한 주기 돈다
    fun tickAfter(m: Long): EvaluationRunner.Summary {
        clock.now = minute(m + 1).plusSeconds(35)
        return runner.runOnce()
    }
    fun events(state: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_events WHERE alert_rule_id = ? AND state = ?", Int::class.java, ruleId, state,
    )!!
    fun outbox(transition: String) = jdbc.queryForList(
        "SELECT o.alert_channel_id FROM notification_outbox o JOIN alert_events e ON e.id = o.alert_event_id WHERE e.alert_rule_id = ? AND o.transition = ?",
        Long::class.java, ruleId, transition,
    )
    fun unknownStreak() = jdbc.queryForObject(
        "SELECT consecutive_unknown FROM alert_evaluation_states WHERE alert_rule_id = ?", Int::class.java, ruleId,
    )!!

    Given("5xx 가 3분 이어지면") {
        (0L..2L).forEach { fake.percentByMinute[minute(it)] = 10 }

        When("매 분 한 주기씩 돌면") {
            val summaries = (0L..2L).map { tickAfter(it) }

            Then("세 번째 버킷에서 발화한다 (N=3)") {
                summaries.map { it.fired } shouldBe listOf(0, 0, 1)
                events("FIRING") shouldBe 1
            }

            Then("발송 의도는 켜진 채널에만 1건 생긴다") {
                outbox("FIRING") shouldBe listOf(onId)
            }
        }

        When("같은 시각에 주기를 또 돌면 (재시도 · 다른 탐지 인스턴스)") {
            val again = tickAfter(2)

            Then("같은 버킷은 무시되고 사건은 그대로 1건이다") {
                again.applied shouldBe 0
                events("FIRING") shouldBe 1
            }
        }
    }

    Given("정상이 2분 이어지면") {
        (3L..4L).forEach { fake.percentByMinute[minute(it)] = 0 }

        When("두 주기를 돌면") {
            val summaries = (3L..4L).map { tickAfter(it) }

            Then("두 번째에서 해제되고 복구 발송 의도가 같은 채널에 생긴다 (M=2)") {
                summaries.map { it.resolved } shouldBe listOf(0, 1)
                events("RESOLVED") shouldBe 1
                outbox("RESOLVED") shouldBe listOf(onId)
            }
        }
    }

    Given("조회가 실패하면") {
        fake.percentByMinute[minute(5)] = 50
        fake.fail = true

        When("한 주기를 돌면") {
            val summary = tickAfter(5)

            Then("사건을 만들지 않고 판정 불가로 센다 (정상으로 치환하지 않는다)") {
                summary.queryFailures shouldBe 1
                summary.fired shouldBe 0
                unknownStreak() shouldBe 1
            }
        }
    }
})

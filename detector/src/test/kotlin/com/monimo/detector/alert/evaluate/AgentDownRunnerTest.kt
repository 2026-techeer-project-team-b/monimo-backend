package com.monimo.detector.alert.evaluate

import com.monimo.detector.support.MutableClock
import com.monimo.detector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val T0: Instant = Instant.parse("2026-10-05T10:00:00Z")

// 조회 파트 agents/active 대신 쓰는 가짜. 키마다 마지막 수신 시각을 정해 두면 그대로 돌려준다
class FakeAgentActivity : AgentActivityClient {
    @Volatile var fail = false
    val lastSignal = mutableMapOf<Pair<String, String>, Instant>()  // (서비스, 키) → 마지막 수신

    override fun fetchActive(from: Instant, to: Instant): List<AgentSignal> {
        if (fail) throw AgentActivityQueryException("주입한 장애: agents/active 500")
        return lastSignal.filterValues { !it.isBefore(from) && it.isBefore(to) }
            .map { (k, at) -> AgentSignal(k.first, k.second, at) }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AgentDownTestConfig {
    @Bean @Primary fun testClock() = MutableClock(T0)
    @Bean @Primary fun fakeAgentActivity() = FakeAgentActivity()
}

// 실제 PG + 가짜 agents/active. 키 단위로는 상태만, 서비스에 살아 있는 키가 0 일 때만 경보 (docs/alert/40-agent-down.md D10 · D11)
// AGENT_DOWN 은 N=1 (agentDownFireAfter), M=2 (전역)
@SpringBootTest(properties = ["monimo.alert.schedule.enabled=false"])
@Import(TestInfraConfig::class, AgentDownTestConfig::class)
class AgentDownRunnerTest(
    runner: AgentDownRunner,
    evaluationRunner: EvaluationRunner,
    evaluationAge: LastSuccessAge,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeAgentActivity,
) : BehaviorSpec({

    fun app(name: String) = jdbc.queryForObject("INSERT INTO applications (name) VALUES (?) RETURNING id", Long::class.java, name)!!
    val orderApp = app("down-order")
    val payApp = app("down-pay")

    val channelId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('down-ch', 'SLACK', '{}'::jsonb, true) RETURNING id", Long::class.java,
    )!!
    fun rule(appId: Long): Long {
        val id = jdbc.queryForObject(
            """
            INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, '파드 없음', 'AGENT_DOWN', 'GT', 0, 60, 'CRITICAL') RETURNING id
            """,
            Long::class.java, UUID.randomUUID(), appId,
        )!!
        jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", id, channelId)
        return id
    }
    val orderRule = rule(orderApp)
    rule(payApp)

    // 적재 처리기가 하는 등록을 흉내 낸다
    fun register(appId: Long, key: String) =
        jdbc.update("INSERT INTO agents (application_id, agent_key) VALUES (?, ?) ON CONFLICT (agent_key) DO NOTHING", appId, key)
    listOf("order-a", "order-b").forEach { register(orderApp, it) }
    register(payApp, "pay-a")

    fun status(key: String) = jdbc.queryForObject("SELECT status FROM agents WHERE agent_key = ?", String::class.java, key)
    fun events(ruleId: Long, state: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_events WHERE alert_rule_id = ? AND state = ?", Int::class.java, ruleId, state,
    )!!
    fun lastOutboxText(ruleId: Long) = jdbc.queryForObject(
        """
        SELECT o.payload::text FROM notification_outbox o JOIN alert_events e ON e.id = o.alert_event_id
        WHERE e.alert_rule_id = ? ORDER BY o.id DESC LIMIT 1
        """,
        String::class.java, ruleId,
    )!!

    // m 분에 한 주기 돈다. live 에 든 키는 그 직전에 데이터를 보낸 것으로 친다
    fun tickAt(m: Long, vararg live: Pair<String, String>): AgentDownRunner.Summary {
        clock.now = T0.plus(Duration.ofMinutes(m)).plusSeconds(5)
        live.forEach { fake.lastSignal[it] = clock.now.minusSeconds(3) }
        return runner.runOnce()
    }

    Given("모든 파드가 데이터를 보낸다") {
        val s = tickAt(0, "down-order" to "order-a", "down-order" to "order-b", "down-pay" to "pay-a")

        Then("경보 없이 키가 전부 UP 이 된다") {
            s.fired shouldBe 0
            listOf("order-a", "order-b", "pay-a").map(::status) shouldBe listOf("UP", "UP", "UP")
        }
    }

    Given("order 파드 하나만 끊긴다 (90초 넘게)") {
        val s = tickAt(2, "down-order" to "order-b", "down-pay" to "pay-a")

        Then("끊긴 키만 DOWN, 서비스에 살아 있는 키가 있으니 경보는 없다 (D10)") {
            status("order-a") shouldBe "DOWN"
            status("order-b") shouldBe "UP"
            s.fired shouldBe 0
            events(orderRule, "FIRING") shouldBe 0
        }
    }

    Given("배포처럼 옛 키가 끊기고 아직 등록 전인 새 키가 데이터를 보낸다") {
        val s = tickAt(4, "down-order" to "order-new", "down-pay" to "pay-a")

        Then("새 키를 응답에서 세므로 서비스는 살아 있다 → 경보 없음") {
            s.fired shouldBe 0
            status("order-b") shouldBe "DOWN"
        }
    }

    Given("order 서비스의 모든 키가 끊긴다") {
        val s = tickAt(6, "down-pay" to "pay-a")

        Then("바로 발화한다 (AGENT_DOWN 은 N=1) — 서비스당 1건") {
            s.fired shouldBe 1
            events(orderRule, "FIRING") shouldBe 1
        }

        Then("알림 문구는 기준값 비교가 아니라 '파드 없음' 판정이고, 관측값은 살아 있는 키 수 0") {
            val payload = lastOutboxText(orderRule)
            payload shouldContain "\"metric_kind\": \"AGENT_DOWN\""
            payload shouldContain "\"observed_value\": 0"
        }
    }

    Given("같은 분에 한 번 더 돈다 (15초 주기)") {
        clock.now = T0.plus(Duration.ofMinutes(6)).plusSeconds(20)
        val s = runner.runOnce()

        Then("같은 버킷이라 무시되고 사건은 1건 그대로") {
            s.applied shouldBe 0
            events(orderRule, "FIRING") shouldBe 1
        }
    }

    Given("파이프라인이 멈춰 어느 서비스에서도 데이터가 없다") {
        fake.lastSignal.clear()
        val s = tickAt(8)

        Then("판정 불가 — pay 까지 울리지 않고, 키 상태도 건드리지 않는다 (파수꾼 몫)") {
            s.fired shouldBe 0
            s.statusChanged shouldBe 0
            status("pay-a") shouldBe "UP"
            events(orderRule, "FIRING") shouldBe 1  // 열린 사건은 닫지 않는다
        }
    }

    Given("agents/active 호출이 실패한다") {
        fake.fail = true
        val s = tickAt(9)
        fake.fail = false

        Then("판정 불가로 세고 상태를 바꾸지 않는다") {
            s.queryFailures shouldBe 1
            s.fired shouldBe 0
            s.statusChanged shouldBe 0
        }
    }

    Given("order 가 다시 살아나 2분 이어진다") {
        val first = tickAt(10, "down-order" to "order-new", "down-pay" to "pay-a")
        val second = tickAt(11, "down-order" to "order-new", "down-pay" to "pay-a")

        Then("두 번째에서 해제된다 (M=2)") {
            first.resolved shouldBe 0
            second.resolved shouldBe 1
            events(orderRule, "RESOLVED") shouldBe 1
        }
    }

    Given("DOWN 된 지 24시간 지난 키만 남은 서비스") {
        val lonelyApp = app("down-lonely")
        val lonelyRule = rule(lonelyApp)
        register(lonelyApp, "lonely-a")
        jdbc.update(
            "UPDATE agents SET status = 'DOWN', updated_at = ? WHERE agent_key = 'lonely-a'",
            Timestamp.from(T0.plus(Duration.ofMinutes(12)).minus(Duration.ofHours(25))),
        )
        val s = tickAt(12, "down-order" to "order-new", "down-pay" to "pay-a")

        Then("감시 대상 키가 없으므로 판정 불가 — 오래전에 사라진 파드로 울리지 않는다") {
            s.fired shouldBe 0
            events(lonelyRule, "FIRING") shouldBe 0
        }
    }

    Given("스케줄러 한 주기(서비스 단위 평가 + AGENT_DOWN)가 끝까지 돈다") {
        Thread.sleep(50)
        val before = evaluationAge.seconds()
        EvaluationScheduler(evaluationRunner, runner, evaluationAge).tick()

        Then("낡음 게이지가 0 근처로 돌아간다 (ADR #54)") {
            (evaluationAge.seconds() < before) shouldBe true
        }
    }
})

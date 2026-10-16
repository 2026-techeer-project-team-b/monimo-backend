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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val G0: Instant = Instant.parse("2026-10-16T10:00:00Z")

// 조회 파트 gc-time 대신 쓰는 가짜. (파드 키, 1분 시작) 마다 GC 시간(ms)을 정해 두면 그 줄만 돌려준다 — 안 정한 1분은 줄이 없다
class FakeGcTime : GcTimeClient {
    @Volatile var fail = false
    val msByMinute = mutableMapOf<Pair<String, Instant>, Long>()

    override fun fetch(from: Instant, to: Instant): List<GcTimeBucket> {
        if (fail) throw GcTimeQueryException("주입한 장애: gc-time 500")
        return msByMinute.filterKeys { (_, start) -> !start.isBefore(from) && start.isBefore(to) }
            .map { (k, ms) -> GcTimeBucket("gc-order", k.first, k.second, BigDecimal.valueOf(ms), 5) }
    }
}

// 자동 스레드 덤프 문 대신 쓰는 가짜. 불린 파드를 적어 둔다
class FakeThreadDump : ThreadDumpClient {
    @Volatile var fail = false
    val calls = mutableListOf<UUID>()

    override fun requestAuto(agentUuid: UUID): AutoDumpResult {
        calls += agentUuid
        if (fail) throw ThreadDumpRequestException("주입한 장애: thread-dumps 503")
        return AutoDumpResult(skipped = false, dumpUuid = UUID.randomUUID().toString())
    }
}

@TestConfiguration(proxyBeanMethods = false)
class GcTimeTestConfig {
    @Bean @Primary fun testClock() = MutableClock(G0)
    @Bean @Primary fun fakeGcTime() = FakeGcTime()
    @Bean @Primary fun fakeThreadDump() = FakeThreadDump()
}

// 실제 PG + 가짜 gc-time · 가짜 덤프 문. 규칙은 서비스에 걸고 판정 · 사건은 파드마다 (docs/alert/80-gc-time.md). N=3 · M=2
@SpringBootTest(properties = ["monimo.alert.schedule.enabled=false"])
@Import(TestInfraConfig::class, GcTimeTestConfig::class)
class GcTimeRunnerTest(
    runner: GcTimeRunner,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeGcTime,
    dumps: FakeThreadDump,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('gc-order') RETURNING id", Long::class.java)!!
    val channelId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('gc-ch', 'SLACK', '{}'::jsonb, true) RETURNING id", Long::class.java,
    )!!
    // 1분 동안 GC 에 6000ms(10%) 넘게 쓰면 위반
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, ?, '주문 GC 과다', 'GC_TIME', 'GT', 6000, 60, 'WARNING') RETURNING id
        """,
        Long::class.java, UUID.randomUUID(), appId,
    )!!
    jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", ruleId, channelId)

    // 적재 처리기가 하는 등록을 흉내 낸다
    fun register(key: String, status: String = "UNKNOWN") = jdbc.queryForObject(
        "INSERT INTO agents (application_id, agent_key, status) VALUES (?, ?, ?) RETURNING agent_uuid", UUID::class.java, appId, key, status,
    )!!
    val podA = register("gc-a")
    val podB = register("gc-b")
    register("gc-old", status = "DOWN")
    fun agentId(key: String) = jdbc.queryForObject("SELECT id FROM agents WHERE agent_key = ?", Long::class.java, key)!!

    fun minute(m: Long): Instant = G0.plus(Duration.ofMinutes(m))
    fun gc(key: String, m: Long, ms: Long) { fake.msByMinute[key to minute(m)] = ms }
    // m 분 버킷이 끝나고 settle(30초)이 지난 시각으로 시계를 옮긴 뒤 한 주기 돈다
    fun tickAfter(m: Long): GcTimeRunner.Summary {
        clock.now = minute(m + 1).plusSeconds(35)
        return runner.runOnce()
    }
    fun events(key: String, state: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_events WHERE alert_rule_id = ? AND agent_id = ? AND state = ?", Int::class.java, ruleId, agentId(key), state,
    )!!
    fun stateRows(key: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_evaluation_states WHERE alert_rule_id = ? AND agent_id = ?", Int::class.java, ruleId, agentId(key),
    )!!
    fun unknownStreak(key: String) = jdbc.queryForObject(
        "SELECT consecutive_unknown FROM alert_evaluation_states WHERE alert_rule_id = ? AND agent_id = ?", Int::class.java, ruleId, agentId(key),
    )!!
    fun lastFiringPayload(key: String) = jdbc.queryForObject(
        """
        SELECT o.payload::text FROM notification_outbox o JOIN alert_events e ON e.id = o.alert_event_id
        WHERE e.alert_rule_id = ? AND e.agent_id = ? AND o.transition = 'FIRING' ORDER BY o.id DESC LIMIT 1
        """,
        String::class.java, ruleId, agentId(key),
    )!!

    Given("파드 A 만 GC 에 1분 7초씩 3분 연속 쓴다 (B 는 0.1초)") {
        (0L..2L).forEach { gc("gc-a", it, 7000); gc("gc-b", it, 100) }

        When("매 분 한 주기씩 돌면") {
            val summaries = (0L..2L).map { tickAfter(it) }

            Then("세 번째 버킷에서 A 만 발화한다 (N=3, 파드마다 따로)") {
                summaries.map { it.fired } shouldBe listOf(0, 0, 1)
                events("gc-a", "FIRING") shouldBe 1
                events("gc-b", "FIRING") shouldBe 0
            }

            Then("발화 순간 A 의 스레드 덤프를 한 번 부른다") {
                dumps.calls shouldBe listOf(podA)
                summaries.last().dumps shouldBe 1
            }

            Then("알림 payload 에 파드 이름표가 실린다") {
                val payload = lastFiringPayload("gc-a")
                payload shouldContain "\"metric_kind\": \"GC_TIME\""
                payload shouldContain "\"agent_key\": \"gc-a\""
            }

            Then("DOWN 파드는 판정하지 않는다 (상태 줄을 만들지 않는다)") {
                stateRows("gc-old") shouldBe 0
            }
        }

        When("같은 시각에 주기를 또 돌면") {
            val again = tickAfter(2)

            Then("같은 버킷은 무시되고 덤프도 다시 부르지 않는다") {
                again.applied shouldBe 0
                dumps.calls.size shouldBe 1
            }
        }
    }

    Given("A 가 2분 정상으로 돌아오고 B 는 그 사이 GC 값이 안 온다") {
        (3L..4L).forEach { gc("gc-a", it, 200) }

        When("두 주기를 돌면") {
            val summaries = (3L..4L).map { tickAfter(it) }

            Then("A 는 두 번째에서 해제된다 (M=2)") {
                summaries.map { it.resolved } shouldBe listOf(0, 1)
                events("gc-a", "RESOLVED") shouldBe 1
            }

            Then("B 의 빈 1분은 0ms 로 치지 않고 판정 불가로 센다") {
                unknownStreak("gc-b") shouldBe 2
            }
        }
    }

    Given("B 가 3분 넘게 GC 과다인데 덤프 문이 실패한다") {
        (5L..7L).forEach { gc("gc-b", it, 9000) }
        dumps.fail = true

        When("세 주기를 돌면") {
            val summaries = (5L..7L).map { tickAfter(it) }

            Then("덤프 실패와 상관없이 B 의 사건 · 알림은 나간다") {
                summaries.last().fired shouldBe 1
                summaries.last().dumpFailures shouldBe 1
                events("gc-b", "FIRING") shouldBe 1
                lastFiringPayload("gc-b") shouldContain "\"agent_key\": \"gc-b\""
            }
        }
    }

    Given("아직 agents 표에 등록되지 않은 파드가 GC 값을 보낸다") {
        dumps.fail = false
        (8L..10L).forEach { gc("gc-new", it, 9000) }
        val summaries = (8L..10L).map { tickAfter(it) }

        Then("기록할 파드 식별자(agent_id FK)가 없어 판정하지 않는다 — 등록된 뒤 다음 주기부터 본다") {
            summaries.sumOf { it.fired } shouldBe 0
        }
    }

    Given("gc-time 조회가 실패하면") {
        fake.fail = true

        When("한 주기를 돌면") {
            val summary = tickAfter(11)

            Then("사건을 만들지 않고 판정 불가로 센다 (정상으로 치환하지 않는다)") {
                summary.queryFailures shouldBe 1
                summary.fired shouldBe 0
            }
        }
    }
})

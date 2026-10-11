package com.monimo.detector.alert.evaluate

import com.monimo.detector.alert.record.EvaluationRecorder
import com.monimo.detector.alert.record.EvaluationTarget
import com.monimo.detector.alert.record.RuleSnapshot
import com.monimo.detector.alert.state.AlertOperator
import com.monimo.detector.alert.state.AlertTarget
import com.monimo.detector.alert.state.Condition
import com.monimo.detector.alert.state.Evaluation
import com.monimo.detector.alert.state.Outcome
import com.monimo.detector.alert.state.Transition
import com.monimo.detector.alert.state.UnknownReason
import com.monimo.detector.alert.state.Verdict
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

// 평가 한 주기: 켜진 규칙 읽기 → 서비스별 조회 한 번 → 규칙 × 완료된 버킷마다 기록기에 넣는다.
// 같은 버킷을 다음 주기에 또 넣어도 상태머신이 DUPLICATE_BUCKET 으로 무시하므로, 멈췄다 돌아와도 빠진 버킷을 따라잡는다
@Component
class EvaluationRunner(
    private val jdbc: JdbcTemplate,
    private val client: ServiceHealthClient,
    private val recorder: EvaluationRecorder,
    private val props: EvaluationProperties,
    private val clock: Clock,
) {
    data class Summary(val rules: Int, val applied: Int, val fired: Int, val resolved: Int, val queryFailures: Int)

    fun runOnce(): Summary {
        val now = clock.instant()
        val ends = BucketValues.settledBucketEnds(now, props.settleDelay, props.maxStaleness)
        val targets = loadTargets()
        if (ends.isEmpty() || targets.isEmpty()) return Summary(targets.size, 0, 0, 0, 0)

        var applied = 0
        var fired = 0
        var resolved = 0
        var queryFailures = 0
        targets.groupBy { it.rule.serviceName }.forEach { (service, rules) ->
            val from = ends.first().minusSeconds(rules.maxOf { it.rule.windowSec }.toLong())
            val buckets = try {
                client.fetch(service, from, ends.last()).associateBy { it.start }
            } catch (e: Exception) {
                log.warn("service-health 조회 실패 service={} : {}", service, e.message)
                queryFailures++
                null
            }
            rules.forEach { target ->
                // 조회 실패는 가장 최근 버킷 하나에만 판정 불가로 남긴다. 정상으로 치환하지 않는다
                val verdicts = if (buckets == null) {
                    listOf(Verdict.Unknown(ends.last(), UnknownReason.QUERY_FAILED))
                } else {
                    val condition = Condition(target.rule.operator, target.rule.threshold, props.maxStaleness)
                    ends.map { end -> condition.judge(end, BucketValues.valueAt(target.rule.metricKind, target.rule.windowSec, end, buckets), now) }
                }
                verdicts.forEach { verdict ->
                    try {
                        val outcome = recorder.record(target, Evaluation(target.rule.version.toLong(), verdict))
                        if (outcome is Outcome.Applied) {
                            applied++
                            when (outcome.transition) {
                                is Transition.Fired -> fired++
                                is Transition.Resolved -> resolved++
                                null -> Unit
                            }
                        }
                    } catch (e: Exception) {
                        // 규칙 하나의 실패가 다른 규칙 평가를 막지 않게 한다
                        log.error("평가 기록 실패 rule={} bucketEnd={}", target.rule.uuid, verdict.bucketEnd, e)
                    }
                }
            }
        }
        return Summary(targets.size, applied, fired, resolved, queryFailures)
    }

    // 켜진 규칙 중 서비스 단위(5XX · 4XX · P95)만. 제외된 서비스는 빠진다.
    // alert_rules 는 API 서버 표(ADR #39: 탐지는 읽기만), applications 는 서비스 이름 · UUID 를 얻으려고 읽는다
    private fun loadTargets(): List<EvaluationTarget> = jdbc.query(
        """
        SELECT r.id, r.alert_rule_uuid, r.version, r.name, a.name AS service_name, a.application_uuid,
               r.metric_kind, r.operator, r.threshold, r.window_sec, r.severity
        FROM alert_rules r JOIN applications a ON a.id = r.application_id
        WHERE r.enabled AND a.deleted_at IS NULL AND r.metric_kind IN (${BucketValues.SUPPORTED.joinToString { "'$it'" }})
        ORDER BY r.id
        """,
    ) { rs, _ ->
        val rule = RuleSnapshot(
            id = rs.getLong("id"),
            uuid = rs.getObject("alert_rule_uuid", UUID::class.java),
            version = rs.getInt("version"),
            name = rs.getString("name"),
            serviceName = rs.getString("service_name"),
            metricKind = rs.getString("metric_kind"),
            operator = AlertOperator.valueOf(rs.getString("operator")),
            threshold = rs.getBigDecimal("threshold") ?: BigDecimal.ZERO,
            windowSec = rs.getInt("window_sec"),
            severity = rs.getString("severity"),
        )
        EvaluationTarget(rule, AlertTarget.Service(rs.getObject("application_uuid", UUID::class.java)), agentId = null)
    }

    private companion object {
        val log = LoggerFactory.getLogger(EvaluationRunner::class.java)
    }
}

// 주기 실행. 여러 탐지 인스턴스가 같이 돌아도 기록기가 상태 행을 잠그고 같은 버킷을 무시하므로 사건은 하나다
class EvaluationScheduler(
    private val runner: EvaluationRunner,
    private val agentDown: AgentDownRunner,
    private val age: LastSuccessAge,
) {
    private val log = LoggerFactory.getLogger(EvaluationScheduler::class.java)

    @Scheduled(fixedDelayString = "\${monimo.alert.schedule.interval:15s}")
    fun tick() {
        val s = runner.runOnce()
        if (s.fired + s.resolved + s.queryFailures > 0) {
            log.info("평가 규칙={} 반영={} 발화={} 해제={} 조회실패={}", s.rules, s.applied, s.fired, s.resolved, s.queryFailures)
        }
        // 한쪽 조회가 실패해도 다른 쪽은 돈다 (각 runOnce 가 조회 실패를 판정 불가로 삼킨다)
        val a = agentDown.runOnce()
        if (a.fired + a.resolved + a.queryFailures + a.statusChanged > 0) {
            log.info("AGENT_DOWN 규칙={} 반영={} 발화={} 해제={} 조회실패={} 상태변경={}", a.rules, a.applied, a.fired, a.resolved, a.queryFailures, a.statusChanged)
        }
        // 두 평가가 PG 를 읽고 끝까지 돌았다. API 서버 조회 실패는 각 runOnce 가 판정 불가로 삼키므로 여기까지 오고,
        // PG 가 막히거나 실패하면 예외 · 블록으로 여기 오지 못해 게이지가 커진다
        age.markSuccess()
    }
}

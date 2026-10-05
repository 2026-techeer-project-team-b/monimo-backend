package com.monimo.detector.alert.evaluate

import com.monimo.detector.alert.record.EvaluationRecorder
import com.monimo.detector.alert.record.EvaluationTarget
import com.monimo.detector.alert.record.RuleSnapshot
import com.monimo.detector.alert.state.AlertOperator
import com.monimo.detector.alert.state.AlertTarget
import com.monimo.detector.alert.state.Evaluation
import com.monimo.detector.alert.state.Outcome
import com.monimo.detector.alert.state.Transition
import com.monimo.detector.alert.state.UnknownReason
import com.monimo.detector.alert.state.Verdict
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

// AGENT_DOWN 한 주기 (docs/alert/40-agent-down.md).
//
// 파드 키(agent_key)는 OTel 에이전트가 기동 때 만든 UUID 라 컨테이너가 재시작되면 바뀐다. 그래서 배포 교체와 크래시 재시작이
// 똑같이 "옛 키 끊김 + 새 키"로 보이고, 키 하나하나로는 둘을 가를 수 없다. 그래서
//   - 키가 끊기면 agents.status 만 DOWN 으로 바꾸고 알리지 않는다 (D10)
//   - 경보는 서비스 단위: 그 서비스에 최근 데이터를 보낸 키가 하나도 없으면 위반 (D11)
// 판정은 1분 버킷(평가한 분)마다 한 번. 같은 분의 두 번째 평가는 상태머신이 DUPLICATE_BUCKET 으로 무시한다
@Component
class AgentDownRunner(
    private val jdbc: JdbcTemplate,
    private val client: AgentActivityClient,
    private val recorder: EvaluationRecorder,
    private val props: EvaluationProperties,
    private val clock: Clock,
) {
    data class Summary(val rules: Int, val applied: Int, val fired: Int, val resolved: Int, val queryFailures: Int, val statusChanged: Int)

    // 감시 대상 키 한 줄 (agents 표, 적재 처리기가 등록)
    private data class Agent(val id: Long, val applicationId: Long, val agentKey: String, val status: String)

    fun runOnce(): Summary {
        val now = clock.instant()
        val cfg = props.agentDown
        val signals = try {
            client.fetchActive(now.minus(cfg.lookback), now)
        } catch (e: Exception) {
            log.warn("agents/active 조회 실패 : {}", e.message)
            null
        }
        // 정해진 시간 안에 데이터를 보낸 키만 "살아 있다"
        val alive = signals?.filter { !it.lastSignalAt.isBefore(now.minus(cfg.silence)) }
        // 어느 서비스에서도 최근 데이터가 없으면 쇼핑몰이 아니라 우리 수집 · 적재가 멈춘 것을 의심한다.
        // 이때 전부 DOWN 으로 바꾸거나 울리면 파이프라인 장애가 "서비스 전부 죽음" 경보 폭주가 된다 (그건 파수꾼 몫)
        val pipelineSilent = alive != null && alive.isEmpty()

        val agents = loadAgents(now)
        val statusChanged = if (cfg.updateStatus && alive != null && !pipelineSilent) syncStatus(agents, alive, now) else 0

        val targets = loadTargets()
        var applied = 0
        var fired = 0
        var resolved = 0
        val bucketEnd = floorToMinute(now)
        val aliveByService = alive?.groupBy { it.serviceName }.orEmpty()
        val agentsByApp = agents.groupBy { it.applicationId }
        targets.forEach { (target, applicationId) ->
            val verdict = when {
                alive == null -> Verdict.Unknown(bucketEnd, UnknownReason.QUERY_FAILED)
                pipelineSilent -> Verdict.Unknown(bucketEnd, UnknownReason.PIPELINE_SILENT)
                else -> {
                    // 키 수는 agents 표가 아니라 응답에서 센다. 방금 뜬 파드는 아직 표에 등록되기 전일 수 있다
                    val liveCount = aliveByService[target.rule.serviceName].orEmpty().map { it.agentKey }.distinct().size
                    when {
                        liveCount > 0 -> Verdict.Ok(bucketEnd, BigDecimal.valueOf(liveCount.toLong()))
                        agentsByApp[applicationId].isNullOrEmpty() -> Verdict.Unknown(bucketEnd, UnknownReason.NO_DATA)  // 본 적 있는 파드가 없다
                        else -> Verdict.Violating(bucketEnd, BigDecimal.ZERO)
                    }
                }
            }
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
                log.error("AGENT_DOWN 기록 실패 rule={} bucketEnd={}", target.rule.uuid, bucketEnd, e)
            }
        }
        return Summary(targets.size, applied, fired, resolved, if (signals == null) 1 else 0, statusChanged)
    }

    // 제외되지 않은 서비스의 파드 키. DOWN 이 된 지 오래된 키는 뺀다 (재시작마다 새 키가 생겨 옛 키가 쌓이므로)
    private fun loadAgents(now: Instant): List<Agent> = jdbc.query(
        """
        SELECT g.id, g.application_id, g.agent_key, g.status
        FROM agents g JOIN applications a ON a.id = g.application_id
        WHERE a.deleted_at IS NULL AND NOT (g.status = 'DOWN' AND g.updated_at < ?)
        """,
        { rs, _ -> Agent(rs.getLong("id"), rs.getLong("application_id"), rs.getString("agent_key"), rs.getString("status")) },
        Timestamp.from(now.minus(props.agentDown.forgetAfter)),
    )

    // 살아 있는 키는 UP, 나머지는 DOWN. 바뀐 줄만 쓴다 (agents 는 적재 처리기가 등록하고 탐지가 생존 상태를 갱신 — ADR #39)
    private fun syncStatus(agents: List<Agent>, alive: List<AgentSignal>, now: Instant): Int {
        val aliveKeys = alive.mapTo(HashSet()) { it.agentKey }
        val changes = agents.mapNotNull { a ->
            val next = if (a.agentKey in aliveKeys) "UP" else "DOWN"
            if (a.status == next) null else a.id to next
        }
        if (changes.isEmpty()) return 0
        jdbc.batchUpdate(
            "UPDATE agents SET status = ?, updated_at = ? WHERE id = ? AND status <> ?",
            changes.map { (id, next) -> arrayOf<Any>(next, Timestamp.from(now), id, next) },
        )
        return changes.size
    }

    // 켜진 AGENT_DOWN 규칙. operator · threshold · window_sec 는 쓰지 않는다 (침묵 기준은 설정 silence, D14)
    private fun loadTargets(): List<Pair<EvaluationTarget, Long>> = jdbc.query(
        """
        SELECT r.id, r.alert_rule_uuid, r.version, r.name, a.id AS application_id, a.name AS service_name, a.application_uuid,
               r.metric_kind, r.operator, r.threshold, r.window_sec, r.severity
        FROM alert_rules r JOIN applications a ON a.id = r.application_id
        WHERE r.enabled AND a.deleted_at IS NULL AND r.metric_kind = 'AGENT_DOWN'
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
        EvaluationTarget(rule, AlertTarget.Service(rs.getObject("application_uuid", UUID::class.java)), agentId = null) to
            rs.getLong("application_id")
    }

    private fun floorToMinute(t: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, 60L) * 60)

    private companion object {
        val log = LoggerFactory.getLogger(AgentDownRunner::class.java)
    }
}

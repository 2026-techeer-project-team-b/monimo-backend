package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.AlertStateMachine
import com.monimo.detector.alert.state.Evaluation
import com.monimo.detector.alert.state.EvaluationPhase
import com.monimo.detector.alert.state.EvaluationState
import com.monimo.detector.alert.state.Outcome
import com.monimo.detector.alert.state.Transition
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

// 평가 한 번을 PG 에 반영한다. 한 트랜잭션 안에서:
//   ① 평가 상태 줄을 (없으면 만들고) FOR UPDATE 로 잠근다
//   ② 순수 상태머신에 넣는다
//   ③ 상태 · 사건 · 발송 의도(outbox)를 같이 저장한다 → 셋 중 하나만 남는 일이 없다
// 외부 채널 호출은 여기서 하지 않는다. 알림 서비스가 커밋된 outbox 를 따로 가져간다
@Service
class EvaluationRecorder(
    private val states: AlertEvaluationStateRepository,
    private val events: AlertEventRepository,
    private val outbox: NotificationOutboxRepository,
    private val entityManager: EntityManager,
    private val machine: AlertStateMachine,
    private val clock: Clock,
    private val hook: TransitionHook,
) {

    @Transactional
    fun record(target: EvaluationTarget, evaluation: Evaluation): Outcome {
        val now = clock.instant()
        val fingerprint = target.fingerprint
        states.insertIfAbsent(fingerprint, target.rule.id, target.agentId, target.rule.version, now)
        val row = checkNotNull(states.lockByFingerprint(fingerprint))

        val outcome = machine.apply(row.toState(), evaluation)
        if (outcome !is Outcome.Applied) return outcome  // 같은 버킷 · 과거 평가: 아무것도 쓰지 않는다

        val next = outcome.state
        when (val t = outcome.transition) {
            is Transition.Fired -> row.activeAlertEventId = fire(target, t, now)
            is Transition.Resolved -> {
                resolve(t, now)
                row.activeAlertEventId = null
            }
            null -> Unit
        }
        row.phase = next.phase
        row.consecutiveBad = next.consecutiveBad
        row.consecutiveGood = next.consecutiveGood
        row.consecutiveUnknown = next.consecutiveUnknown
        row.lastBucketEnd = next.lastBucketEnd
        row.ruleVersion = next.ruleVersion.toInt()
        row.updatedAt = now
        return outcome
    }

    private fun fire(target: EvaluationTarget, fired: Transition.Fired, now: Instant): Long {
        val rule = target.rule
        val event = events.saveAndFlush(
            AlertEventEntity(
                alertEventUuid = fired.eventUuid,
                alertRuleId = rule.id,
                agentId = target.agentId,
                fingerprint = target.fingerprint,
                state = AlertEventState.FIRING,
                observedValue = fired.observedValue,
                firedAt = fired.bucketEnd,
                resolvedAt = null,
                ruleVersion = rule.version,
                metricKind = rule.metricKind,
                operator = rule.operator.name,
                threshold = rule.threshold,
                windowSec = rule.windowSec,
                severity = rule.severity,
                createdAt = now,
            ),
        )
        val eventId = checkNotNull(event.id)
        hook.afterEventSaved(eventId)

        val payload = mapOf(
            "alert_event_uuid" to fired.eventUuid.toString(),
            "transition" to OutboxTransition.FIRING.name,
            "rule_name" to rule.name,
            "service_name" to rule.serviceName,
            "severity" to rule.severity,
            "metric_kind" to rule.metricKind,
            "operator" to rule.operator.name,
            "threshold" to rule.threshold,
            "observed_value" to fired.observedValue,
            "fired_at" to fired.bucketEnd.toString(),
        )
        outbox.saveAll(enabledChannelIds(rule.id).map { NotificationOutboxEntity.pending(eventId, OutboxTransition.FIRING, it, payload, now) })
        return eventId
    }

    private fun resolve(resolved: Transition.Resolved, now: Instant) {
        val event = checkNotNull(events.findByAlertEventUuid(resolved.eventUuid))
        event.state = AlertEventState.RESOLVED
        event.resolvedAt = resolved.bucketEnd
        val eventId = checkNotNull(event.id)
        val firing = outbox.findByAlertEventIdAndTransition(eventId, OutboxTransition.FIRING)
        if (firing.isEmpty()) return  // 발화 때 켜진 채널이 없었다 → 복구도 보낼 곳이 없다
        // 발화 알림의 내용(규칙 이름 등)을 그대로 이어 받는다. 지금 규칙을 다시 읽으면 그 사이 수정된 값이 섞인다
        val payload = firing.first().payload + mapOf(
            "transition" to OutboxTransition.RESOLVED.name,
            "resolved_at" to resolved.bucketEnd.toString(),
        )
        val channelIds = firing.map { it.alertChannelId }.distinct()
        outbox.saveAll(channelIds.map { NotificationOutboxEntity.pending(eventId, OutboxTransition.RESOLVED, it, payload, now) })
    }

    // 규칙-채널 연결은 API 서버 표다. 탐지는 읽기만 한다. 발화 순간 켜져 있는 채널에만 발송 의도를 만든다
    @Suppress("UNCHECKED_CAST")
    private fun enabledChannelIds(alertRuleId: Long): List<Long> =
        (
            entityManager.createNativeQuery(
                """
                SELECT c.id FROM alert_rule_channels rc
                JOIN alert_channels c ON c.id = rc.alert_channel_id
                WHERE rc.alert_rule_id = :ruleId AND c.enabled
                ORDER BY c.id
                """,
            ).setParameter("ruleId", alertRuleId).resultList as List<Number>
            ).map { it.toLong() }

    private fun AlertEvaluationStateEntity.toState() = EvaluationState(
        phase = phase,
        consecutiveBad = consecutiveBad,
        consecutiveGood = consecutiveGood,
        consecutiveUnknown = consecutiveUnknown,
        activeEventUuid = activeAlertEventId?.let { checkNotNull(events.findById(it).orElse(null)).alertEventUuid },
        lastBucketEnd = lastBucketEnd,
        ruleVersion = ruleVersion.toLong(),
    ).also { check((it.phase == EvaluationPhase.FIRING) == (it.activeEventUuid != null)) }
}

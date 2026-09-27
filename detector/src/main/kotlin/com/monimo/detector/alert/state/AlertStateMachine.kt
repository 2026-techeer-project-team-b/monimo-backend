package com.monimo.detector.alert.state

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

// FN-28 발화 · 해제 정책. N · M 을 담을 컬럼이 ERD 에 아직 없어서 값은 설정으로 받는다 (docs/alert/10-state-machine.md)
data class AlertPolicy(
    val fireAfter: Int,       // N: 서로 다른 버킷에서 연속 N회 위반이면 발화
    val resolveAfter: Int,    // M: 연속 M회 정상이면 해제
    // 반영한 버킷 사이가 이보다 벌어지면 "연속"이 끊겼다고 보고 연속 횟수를 0 으로 되돌린다 (null 이면 검사 안 함)
    val maxBucketGap: Duration?,
) {
    init {
        require(fireAfter >= 1) { "fireAfter(N)는 1 이상이어야 합니다: $fireAfter" }
        require(resolveAfter >= 1) { "resolveAfter(M)는 1 이상이어야 합니다: $resolveAfter" }
    }
}

// 평가 한 번의 입력. 평가 식별 = (fingerprint, bucketEnd). 같은 버킷을 여러 번 읽어도 1회로 센다
data class Evaluation(
    val ruleVersion: Long,
    val verdict: Verdict,
)

sealed interface Transition {
    data class Fired(val eventUuid: UUID, val observedValue: BigDecimal, val bucketEnd: Instant) : Transition
    data class Resolved(val eventUuid: UUID, val bucketEnd: Instant) : Transition
}

enum class IgnoreReason {
    DUPLICATE_BUCKET,     // 이미 반영한 버킷 (재시도 · 다른 탐지 인스턴스가 먼저 처리)
    OUT_OF_ORDER_BUCKET,  // 이미 반영한 버킷보다 과거 (늦게 도착한 평가)
    STALE_RULE_VERSION,   // 지금보다 낮은 규칙 판으로 계산한 평가
}

sealed interface Outcome {
    val state: EvaluationState

    // 상태를 바꾸지 않았다. 호출자는 아무것도 저장하지 않는다
    data class Ignored(override val state: EvaluationState, val reason: IgnoreReason) : Outcome

    // 새 상태. transition 이 있으면 사건 INSERT/UPDATE + 발송 의도를 같은 트랜잭션에 넣는다
    data class Applied(override val state: EvaluationState, val transition: Transition?) : Outcome
}

// 경보 상태머신. DB · 시계 · 난수에 기대지 않는 순수 함수라서 입력 순서만으로 결과가 정해진다.
// 동시성(두 탐지가 같은 fingerprint 를 동시에 처리)은 여기서 막지 않는다. 호출자가 상태 행을 잠근 뒤 이 함수를 부르고,
// 이 함수는 같은 버킷 재처리를 Ignored 로 돌려 "두 번째 처리자"를 무해하게 만든다.
class AlertStateMachine(
    private val policy: AlertPolicy,
    private val newEventUuid: () -> UUID = UUID::randomUUID,
) {

    fun apply(current: EvaluationState, evaluation: Evaluation): Outcome {
        if (evaluation.ruleVersion < current.ruleVersion) {
            return Outcome.Ignored(current, IgnoreReason.STALE_RULE_VERSION)
        }
        val bucketEnd = evaluation.verdict.bucketEnd
        val last = current.lastBucketEnd
        if (last != null) {
            if (bucketEnd == last) return Outcome.Ignored(current, IgnoreReason.DUPLICATE_BUCKET)
            if (bucketEnd.isBefore(last)) return Outcome.Ignored(current, IgnoreReason.OUT_OF_ORDER_BUCKET)
        }

        val base = current
            .let { if (evaluation.ruleVersion > it.ruleVersion) it.resetStreaks().copy(ruleVersion = evaluation.ruleVersion) else it }
            .let { if (last != null && isGapTooWide(last, bucketEnd)) it.resetStreaks() else it }
            .copy(lastBucketEnd = bucketEnd)

        return when (val verdict = evaluation.verdict) {
            is Verdict.Violating -> onViolating(base, verdict)
            is Verdict.Ok -> onOk(base, verdict)
            is Verdict.Unknown -> Outcome.Applied(base.resetStreaks().copy(consecutiveUnknown = base.consecutiveUnknown + 1), null)
        }
    }

    private fun onViolating(s: EvaluationState, v: Verdict.Violating): Outcome {
        val bad = s.consecutiveBad + 1
        val counted = s.copy(consecutiveBad = bad, consecutiveGood = 0, consecutiveUnknown = 0)
        return when {
            // 발화 중 반복 위반: 새 사건을 만들지 않는다 (중복 억제)
            s.phase == EvaluationPhase.FIRING -> Outcome.Applied(counted, null)
            bad >= policy.fireAfter -> {
                val eventUuid = newEventUuid()
                Outcome.Applied(
                    counted.copy(phase = EvaluationPhase.FIRING, activeEventUuid = eventUuid),
                    Transition.Fired(eventUuid, v.value, v.bucketEnd),
                )
            }
            else -> Outcome.Applied(counted.copy(phase = EvaluationPhase.PENDING), null)
        }
    }

    private fun onOk(s: EvaluationState, v: Verdict.Ok): Outcome {
        val good = s.consecutiveGood + 1
        val counted = s.copy(consecutiveBad = 0, consecutiveGood = good, consecutiveUnknown = 0)
        return when {
            s.phase != EvaluationPhase.FIRING -> Outcome.Applied(counted.copy(phase = EvaluationPhase.NORMAL), null)
            good >= policy.resolveAfter -> Outcome.Applied(
                counted.copy(phase = EvaluationPhase.NORMAL, activeEventUuid = null),
                Transition.Resolved(s.activeEventUuid!!, v.bucketEnd),
            )
            else -> Outcome.Applied(counted, null)
        }
    }

    private fun isGapTooWide(last: Instant, bucketEnd: Instant): Boolean {
        val maxGap = policy.maxBucketGap ?: return false
        return Duration.between(last, bucketEnd) > maxGap
    }

    // 연속 횟수만 지운다. 열린 사건은 닫지 않는다 — 판정할 수 없다는 것이 복구됐다는 뜻은 아니다
    private fun EvaluationState.resetStreaks(): EvaluationState = copy(
        phase = if (phase == EvaluationPhase.FIRING) EvaluationPhase.FIRING else EvaluationPhase.NORMAL,
        consecutiveBad = 0,
        consecutiveGood = 0,
    )
}

package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.AlertOperator
import com.monimo.detector.alert.state.AlertTarget
import com.monimo.detector.alert.state.Fingerprint
import java.math.BigDecimal
import java.util.UUID

// 평가할 때 쓴 규칙 한 판. 규칙 갱신 폴링(10~30초)이 PG 에서 읽어 둔 값이다
data class RuleSnapshot(
    val id: Long,
    val uuid: UUID,
    val version: Int,
    val name: String,
    val serviceName: String,
    val metricKind: String,
    val operator: AlertOperator,
    val threshold: BigDecimal,
    val windowSec: Int,
    val severity: String,
)

// 규칙 + 대상. agentId 는 파드 단위 규칙일 때만 있다
data class EvaluationTarget(
    val rule: RuleSnapshot,
    val target: AlertTarget,
    val agentId: Long?,
) {
    val fingerprint: String = Fingerprint.of(rule.uuid, target)
}

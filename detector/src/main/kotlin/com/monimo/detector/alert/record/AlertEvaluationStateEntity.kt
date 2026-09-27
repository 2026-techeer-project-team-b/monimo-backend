package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.EvaluationPhase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// alert_evaluation_states (제안 표). fingerprint 하나당 한 줄
@Entity
@Table(name = "alert_evaluation_states")
class AlertEvaluationStateEntity(
    @Column(nullable = false, unique = true, length = 64)
    val fingerprint: String,

    @Column(name = "alert_rule_id", nullable = false)
    val alertRuleId: Long,

    @Column(name = "agent_id")
    val agentId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var phase: EvaluationPhase,

    @Column(name = "consecutive_bad", nullable = false)
    var consecutiveBad: Int,

    @Column(name = "consecutive_good", nullable = false)
    var consecutiveGood: Int,

    @Column(name = "consecutive_unknown", nullable = false)
    var consecutiveUnknown: Int,

    @Column(name = "active_alert_event_id")
    var activeAlertEventId: Long?,

    @Column(name = "last_bucket_end")
    var lastBucketEnd: Instant?,

    @Column(name = "rule_version", nullable = false)
    var ruleVersion: Int,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

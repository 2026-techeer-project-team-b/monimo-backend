package com.monimo.detector.alert.state

import java.time.Instant
import java.util.UUID

// 평가 상태 (탐지 내부 전용). 공개 API 의 경보 state 는 FIRING / RESOLVED 두 개뿐이고, 이 값은 밖으로 내보내지 않는다
enum class EvaluationPhase {
    NORMAL,   // 위반 없음
    PENDING,  // 위반 중이지만 아직 N회에 못 미침 → 사건 없음
    FIRING,   // 사건(alert_event) 진행 중. 정상이 M회 쌓이면 RESOLVED 로 닫는다
}

// 규칙 하나 + 대상 하나(= fingerprint 하나)의 평가 상태. JPA Entity 가 아닌 값 객체다
data class EvaluationState(
    val phase: EvaluationPhase,
    val consecutiveBad: Int,
    val consecutiveGood: Int,
    // 판정 불가(조회 실패 · 데이터 없음 · 낡은 데이터)가 몇 번 이어졌나. 탐지 품질 저하 관측용
    val consecutiveUnknown: Int,
    // 지금 열려 있는 사건. FIRING 일 때만 값이 있다
    val activeEventUuid: UUID?,
    // 마지막으로 반영한 집계 버킷의 끝 시각. 같은 버킷 재처리 · 과거 버킷 역행을 막는 기준
    val lastBucketEnd: Instant?,
    // 마지막으로 반영한 규칙 판 번호. 이보다 낮은 판으로 계산한 평가는 버린다
    val ruleVersion: Long,
) {
    init {
        require((phase == EvaluationPhase.FIRING) == (activeEventUuid != null)) {
            "FIRING 일 때만 진행 중 사건이 있어야 합니다: phase=$phase, activeEventUuid=$activeEventUuid"
        }
    }

    companion object {
        fun initial(ruleVersion: Long) = EvaluationState(
            phase = EvaluationPhase.NORMAL,
            consecutiveBad = 0,
            consecutiveGood = 0,
            consecutiveUnknown = 0,
            activeEventUuid = null,
            lastBucketEnd = null,
            ruleVersion = ruleVersion,
        )
    }
}

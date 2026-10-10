package com.monimo.detector.alert.state

import java.security.MessageDigest
import java.util.UUID

// 경보 대상. 5XX_RATE · 4XX_RATE · P95_LATENCY · AGENT_DOWN 은 서비스 단위, CPU · HEAP · GC_TIME 은 파드 단위.
// AGENT_DOWN 이 서비스 단위인 이유: 파드 키가 재시작마다 바뀌어 배포와 크래시를 가를 수 없다 (docs/alert/40-agent-down.md D11)
sealed interface AlertTarget {
    data class Service(val applicationUuid: UUID) : AlertTarget
    data class Agent(val agentUuid: UUID) : AlertTarget
}

// alert_events.fingerprint (VARCHAR(64)). "같은 규칙 + 같은 대상"이면 늘 같은 값이 나온다.
// 장애 회차는 fingerprint 가 아니라 alert_event_uuid 로 구분한다 — 복구 뒤 재발하면 fingerprint 는 같고 사건 UUID 는 새로 생긴다.
// 규칙 판 번호 · 기준값은 넣지 않는다. 기준값을 고쳐도 진행 중 사건이 같은 사건으로 이어져야 하기 때문이다.
object Fingerprint {
    fun of(alertRuleUuid: UUID, target: AlertTarget): String {
        val key = when (target) {
            is AlertTarget.Service -> "$alertRuleUuid|service|${target.applicationUuid}"
            is AlertTarget.Agent -> "$alertRuleUuid|agent|${target.agentUuid}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }  // 64자 16진수
    }
}

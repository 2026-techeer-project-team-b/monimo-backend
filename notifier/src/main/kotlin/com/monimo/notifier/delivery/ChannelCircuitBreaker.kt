package com.monimo.notifier.delivery

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// 채널별 서킷브레이커 (E8). 채널이 계속 실패하면 잠시 호출을 멈추고 작업을 재예약한다.
//  CLOSED    : 호출한다. 연속 실패가 threshold 번이면 OPEN
//  OPEN      : 호출하지 않는다. openUntil 까지 기다린다
//  HALF_OPEN : openUntil 이 지나면 시험 호출 1번만 허용. 응답하면 CLOSED, 실패면 다시 OPEN
// 회로는 이 인스턴스 메모리에만 있다. 알림 인스턴스가 여러 대면 각자 회로를 가진다
class ChannelCircuitBreaker(
    private val failureThreshold: Int,
    private val openDuration: Duration,
    private val clock: Clock,
) {
    init {
        require(failureThreshold >= 1) { "failureThreshold 는 1 이상이어야 합니다: $failureThreshold" }
    }

    enum class State { CLOSED, OPEN, HALF_OPEN }

    sealed interface Permit {
        data object Allowed : Permit
        // 호출하지 말고 retryAt 에 다시 꺼내라
        data class Rejected(val state: State, val retryAt: Instant) : Permit
    }

    private class Circuit {
        var state = State.CLOSED
        var failures = 0
        var openUntil: Instant = Instant.MIN
        var trialInFlight = false
    }

    private val circuits = ConcurrentHashMap<Long, Circuit>()

    fun acquire(channelId: Long): Permit {
        val c = circuits.computeIfAbsent(channelId) { Circuit() }
        synchronized(c) {
            val now = clock.instant()
            return when (c.state) {
                State.CLOSED -> Permit.Allowed
                State.OPEN ->
                    if (now.isBefore(c.openUntil)) {
                        Permit.Rejected(State.OPEN, c.openUntil)
                    } else {
                        c.state = State.HALF_OPEN
                        c.trialInFlight = true
                        Permit.Allowed
                    }
                // 시험 호출은 한 번에 하나. 다른 워커 스레드는 잠깐 뒤에 다시 본다
                State.HALF_OPEN ->
                    if (c.trialInFlight) {
                        Permit.Rejected(State.HALF_OPEN, now.plus(TRIAL_WAIT))
                    } else {
                        c.trialInFlight = true
                        Permit.Allowed
                    }
            }
        }
    }

    // 공급자가 응답했다 (접수 · 영구 거절). 영구 거절은 채널 설정 문제이지 장애가 아니다
    fun onResponded(channelId: Long) = update(channelId) {
        state = State.CLOSED
        failures = 0
        trialInFlight = false
    }

    // 장애 신호 (5xx · 429 · 연결 실패 · 결과 모름)
    fun onFailure(channelId: Long) = update(channelId) {
        failures++
        trialInFlight = false
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            state = State.OPEN
            openUntil = clock.instant().plus(openDuration)
        }
    }

    fun stateOf(channelId: Long): State = circuits[channelId]?.let { synchronized(it) { it.state } } ?: State.CLOSED

    private fun update(channelId: Long, block: Circuit.() -> Unit) {
        val c = circuits.computeIfAbsent(channelId) { Circuit() }
        synchronized(c) { c.block() }
    }

    private companion object {
        val TRIAL_WAIT: Duration = Duration.ofSeconds(2)
    }
}

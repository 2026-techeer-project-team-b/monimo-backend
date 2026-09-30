package com.monimo.notifier.delivery

import com.monimo.notifier.delivery.ChannelCircuitBreaker.Permit
import com.monimo.notifier.delivery.ChannelCircuitBreaker.State
import com.monimo.notifier.support.MutableClock
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

class ChannelCircuitBreakerTest : BehaviorSpec({

    val t0 = Instant.parse("2026-10-01T00:00:00Z")

    Given("연속 실패 3번이면 여는 회로 (OPEN 30초)") {
        val clock = MutableClock(t0)
        val breaker = ChannelCircuitBreaker(3, Duration.ofSeconds(30), clock)

        When("실패 2번 뒤 응답 1번, 다시 실패 2번이면") {
            repeat(2) { breaker.onFailure(1) }
            breaker.onResponded(1)
            repeat(2) { breaker.onFailure(1) }

            Then("연속이 끊겼으니 아직 CLOSED") {
                breaker.stateOf(1) shouldBe State.CLOSED
                breaker.acquire(1) shouldBe Permit.Allowed
            }
        }

        When("세 번째 연속 실패가 오면") {
            breaker.onFailure(1)

            Then("OPEN 이 되고 30초 뒤로 재예약하라고 한다. 다른 채널은 CLOSED") {
                breaker.stateOf(1) shouldBe State.OPEN
                breaker.acquire(1) shouldBe Permit.Rejected(State.OPEN, t0.plusSeconds(30))
                breaker.acquire(2) shouldBe Permit.Allowed
            }
        }

        When("30초가 지나면") {
            clock.advance(Duration.ofSeconds(30))
            val trial = breaker.acquire(1)
            val second = breaker.acquire(1)

            Then("시험 호출 1번만 허용하고, 그동안 다른 요청은 잠깐 뒤로 미룬다") {
                trial shouldBe Permit.Allowed
                breaker.stateOf(1) shouldBe State.HALF_OPEN
                second.shouldBeInstanceOf<Permit.Rejected>().state shouldBe State.HALF_OPEN
            }
        }

        When("시험 호출이 실패하면") {
            breaker.onFailure(1)

            Then("바로 다시 OPEN (연속 3번을 다시 채우지 않는다)") {
                breaker.stateOf(1) shouldBe State.OPEN
                breaker.acquire(1) shouldBe Permit.Rejected(State.OPEN, clock.now.plusSeconds(30))
            }
        }

        When("또 30초 뒤 시험 호출이 응답하면") {
            clock.advance(Duration.ofSeconds(30))
            breaker.acquire(1)
            breaker.onResponded(1)

            Then("CLOSED 로 돌아온다") {
                breaker.stateOf(1) shouldBe State.CLOSED
                breaker.acquire(1) shouldBe Permit.Allowed
            }
        }
    }
})

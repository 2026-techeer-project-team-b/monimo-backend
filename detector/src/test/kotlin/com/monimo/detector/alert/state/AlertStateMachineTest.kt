package com.monimo.detector.alert.state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID

private val T0: Instant = Instant.parse("2026-09-27T00:00:00Z")
private val STEP: Duration = Duration.ofMinutes(1)

// 'V' = 위반, 'O' = 정상, '?' = 판정 불가. 버킷은 1분씩 증가
private fun verdictOf(c: Char, bucketEnd: Instant): Verdict = when (c) {
    'V' -> Verdict.Violating(bucketEnd, BigDecimal("3.74"))
    'O' -> Verdict.Ok(bucketEnd, BigDecimal("0.20"))
    '?' -> Verdict.Unknown(bucketEnd, UnknownReason.QUERY_FAILED)
    else -> error("알 수 없는 기호: $c")
}

private class Run(policy: AlertPolicy) {
    private var seq = 0
    val machine = AlertStateMachine(policy) { UUID(0, (++seq).toLong()) }
    var state = EvaluationState.initial(ruleVersion = 1)
    var nextBucket: Instant = T0.plus(STEP)
    val transitions = mutableListOf<Transition>()

    fun feed(pattern: String, ruleVersion: Long = 1): Run {
        for (c in pattern) {
            val outcome = machine.apply(state, Evaluation(ruleVersion, verdictOf(c, nextBucket)))
            state = outcome.state
            if (outcome is Outcome.Applied) outcome.transition?.let(transitions::add)
            nextBucket = nextBucket.plus(STEP)
        }
        return this
    }
}

private val N3_M2 = AlertPolicy(fireAfter = 3, resolveAfter = 2, maxBucketGap = Duration.ofMinutes(3))

class AlertStateMachineTest : BehaviorSpec({

    Given("N=3, M=2 규칙") {
        When("위반·위반·정상·위반·위반·위반 순서로 평가하면") {
            val run = Run(N3_M2).feed("VVO")
            val beforeFire = run.transitions.toList()
            run.feed("VVV")

            Then("정상이 끼어든 앞의 두 번은 연속으로 세지 않고, 마지막 3연속에서 처음 발화한다") {
                beforeFire.shouldBeEmpty()
                run.transitions shouldHaveSize 1
                run.transitions[0].shouldBeInstanceOf<Transition.Fired>()
                run.state.phase shouldBe EvaluationPhase.FIRING
            }
        }

        When("발화 뒤 위반이 20번 더 이어지면") {
            val run = Run(N3_M2).feed("VVV").feed("V".repeat(20))

            Then("새 사건을 만들지 않는다 (중복 억제)") {
                run.transitions shouldHaveSize 1
                run.state.activeEventUuid shouldBe (run.transitions[0] as Transition.Fired).eventUuid
            }
        }

        When("발화 중 정상·위반·정상이 번갈아 오면") {
            val run = Run(N3_M2).feed("VVV").feed("OVOVOV")

            Then("M=2 연속 정상이 없으므로 해제하지 않는다 (진동 억제)") {
                run.transitions shouldHaveSize 1
                run.state.phase shouldBe EvaluationPhase.FIRING
            }
        }

        When("발화 뒤 정상이 2번 이어지면") {
            val run = Run(N3_M2).feed("VVV").feed("OO")

            Then("같은 사건을 RESOLVED 로 닫는다") {
                run.transitions shouldHaveSize 2
                val fired = run.transitions[0] as Transition.Fired
                val resolved = run.transitions[1] as Transition.Resolved
                resolved.eventUuid shouldBe fired.eventUuid
                run.state.phase shouldBe EvaluationPhase.NORMAL
                run.state.activeEventUuid.shouldBeNull()
            }
        }

        When("복구 뒤 다시 3번 위반하면") {
            val run = Run(N3_M2).feed("VVV").feed("OO").feed("VVV")

            Then("새 사건 UUID 로 다시 발화한다 (fingerprint 는 같고 장애 회차가 다르다)") {
                val fires = run.transitions.filterIsInstance<Transition.Fired>()
                fires shouldHaveSize 2
                fires[0].eventUuid shouldNotBe fires[1].eventUuid
            }
        }

        When("복구 뒤 1~2번만 위반하면") {
            val run = Run(N3_M2).feed("VVV").feed("OO").feed("VVO")

            Then("PENDING 에서 멈추고 재발화하지 않는다") {
                run.transitions.filterIsInstance<Transition.Fired>() shouldHaveSize 1
                run.state.phase shouldBe EvaluationPhase.NORMAL
            }
        }
    }

    Given("N=2 규칙에 위반·정상이 계속 번갈아 들어오면") {
        val run = Run(AlertPolicy(fireAfter = 2, resolveAfter = 2, maxBucketGap = null)).feed("VO".repeat(50))

        Then("한 번도 발화하지 않는다") {
            run.transitions.shouldBeEmpty()
        }
    }

    Given("N=1, M=1 규칙") {
        val run = Run(AlertPolicy(fireAfter = 1, resolveAfter = 1, maxBucketGap = null))

        When("위반 한 번, 정상 한 번") {
            run.feed("VO")

            Then("바로 발화하고 바로 해제한다") {
                run.transitions.map { it::class } shouldBe listOf(Transition.Fired::class, Transition.Resolved::class)
            }
        }
    }

    Given("판정 불가(조회 실패 · 데이터 없음)") {
        When("발화 중 조회 실패가 10번 이어지면") {
            val run = Run(N3_M2).feed("VVV").feed("?".repeat(10))

            Then("정상으로 치환하지 않으므로 해제하지 않는다") {
                run.transitions shouldHaveSize 1
                run.state.phase shouldBe EvaluationPhase.FIRING
                run.state.consecutiveUnknown shouldBe 10
            }
        }

        When("발화 중 정상 · 조회 실패 · 정상 순서면") {
            val run = Run(N3_M2).feed("VVV").feed("O?O")

            Then("판정 불가가 연속을 끊으므로 해제하지 않는다") {
                run.state.phase shouldBe EvaluationPhase.FIRING
            }
        }

        When("PENDING 중 위반 · 위반 · 조회 실패 · 위반 순서면") {
            val run = Run(N3_M2).feed("VV?V")

            Then("판정 불가가 연속을 끊으므로 발화하지 않는다") {
                run.transitions.shouldBeEmpty()
                run.state.consecutiveBad shouldBe 1
            }
        }
    }

    Given("같은 버킷 · 과거 버킷 · 낡은 규칙 판") {
        val machine = AlertStateMachine(N3_M2)
        val b1 = T0.plus(STEP)
        val b2 = b1.plus(STEP)
        val afterB2 = listOf(b1, b2).fold(EvaluationState.initial(1)) { s, b ->
            machine.apply(s, Evaluation(1, Verdict.Violating(b, BigDecimal.ONE))).state
        }

        When("이미 반영한 버킷을 다시 평가하면 (재시도 · 다른 탐지 인스턴스)") {
            val outcome = machine.apply(afterB2, Evaluation(1, Verdict.Violating(b2, BigDecimal.ONE)))

            Then("DUPLICATE_BUCKET 으로 무시하고 연속 횟수를 올리지 않는다") {
                outcome shouldBe Outcome.Ignored(afterB2, IgnoreReason.DUPLICATE_BUCKET)
                afterB2.consecutiveBad shouldBe 2
            }
        }

        When("반영한 버킷보다 과거 버킷이 늦게 도착하면") {
            val outcome = machine.apply(afterB2, Evaluation(1, Verdict.Ok(b1, BigDecimal.ZERO)))

            Then("OUT_OF_ORDER_BUCKET 으로 무시한다") {
                (outcome as Outcome.Ignored).reason shouldBe IgnoreReason.OUT_OF_ORDER_BUCKET
            }
        }

        When("규칙이 판 2로 바뀐 뒤 판 1로 계산한 평가가 도착하면") {
            val v2 = machine.apply(afterB2, Evaluation(2, Verdict.Violating(b2.plus(STEP), BigDecimal.ONE))).state
            val outcome = machine.apply(v2, Evaluation(1, Verdict.Violating(b2.plus(STEP).plus(STEP), BigDecimal.ONE)))

            Then("판 2 첫 평가는 연속을 새로 센다 (조건이 바뀌었으므로 이전 위반을 이어 세지 않는다)") {
                v2.ruleVersion shouldBe 2
                v2.consecutiveBad shouldBe 1
            }
            Then("판 1 평가는 STALE_RULE_VERSION 으로 무시한다") {
                (outcome as Outcome.Ignored).reason shouldBe IgnoreReason.STALE_RULE_VERSION
            }
        }
    }

    Given("발화 중 규칙 판이 바뀌면") {
        val run = Run(N3_M2).feed("VVV")
        val fired = run.transitions.single() as Transition.Fired
        run.feed("O", ruleVersion = 2)

        Then("열린 사건은 유지한다 (규칙 수정은 복구가 아니다)") {
            run.state.phase shouldBe EvaluationPhase.FIRING
            run.state.activeEventUuid shouldBe fired.eventUuid
            run.state.consecutiveGood shouldBe 1
        }
    }

    Given("버킷 사이가 maxBucketGap(3분)보다 벌어지면") {
        val machine = AlertStateMachine(N3_M2)
        val s = listOf(1L, 2L).fold(EvaluationState.initial(1)) { acc, m ->
            machine.apply(acc, Evaluation(1, Verdict.Violating(T0.plus(STEP.multipliedBy(m)), BigDecimal.ONE))).state
        }
        val outcome = machine.apply(s, Evaluation(1, Verdict.Violating(T0.plus(STEP.multipliedBy(10)), BigDecimal.ONE)))

        Then("연속이 끊긴 것으로 보고 1부터 다시 센다") {
            outcome.state.consecutiveBad shouldBe 1
            (outcome as Outcome.Applied).transition.shouldBeNull()
        }
    }

    Given("무작위 1만 개 평가") {
        val random = Random(42)
        val run = Run(N3_M2)
        val pattern = String(CharArray(10_000) { "VVVOO?"[random.nextInt(6)] })
        run.feed(pattern)

        Then("발화와 해제가 번갈아 나오고, 해제는 늘 직전 발화의 사건을 닫는다") {
            var open: UUID? = null
            for (t in run.transitions) {
                when (t) {
                    is Transition.Fired -> { open.shouldBeNull(); open = t.eventUuid }
                    is Transition.Resolved -> { open.shouldNotBeNull(); t.eventUuid shouldBe open; open = null }
                }
            }
            run.state.activeEventUuid shouldBe open
        }
    }

    Given("잘못된 정책") {
        Then("N 이나 M 이 0 이면 만들 수 없다") {
            shouldThrow<IllegalArgumentException> { AlertPolicy(0, 1, null) }
            shouldThrow<IllegalArgumentException> { AlertPolicy(1, 0, null) }
        }
    }
})

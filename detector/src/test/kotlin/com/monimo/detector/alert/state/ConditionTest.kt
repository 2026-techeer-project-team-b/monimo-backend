package com.monimo.detector.alert.state

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ConditionTest : BehaviorSpec({

    Given("기준값 1 과 같은 관측값 1") {
        val one = BigDecimal("1.0000")

        Then("GT · LT 는 위반이 아니고 GTE · LTE 는 위반이다 (경계값)") {
            AlertOperator.GT.isViolated(BigDecimal.ONE, one) shouldBe false
            AlertOperator.GTE.isViolated(BigDecimal.ONE, one) shouldBe true
            AlertOperator.LT.isViolated(BigDecimal.ONE, one) shouldBe false
            AlertOperator.LTE.isViolated(BigDecimal.ONE, one) shouldBe true
        }
    }

    Given("5xx 비율 > 1, 신선도 한도 3분인 조건") {
        val condition = Condition(AlertOperator.GT, BigDecimal.ONE, maxStaleness = Duration.ofMinutes(3))
        val now = Instant.parse("2026-09-27T00:10:00Z")

        When("1분 전 버킷의 값이 3.74 면") {
            Then("위반") {
                condition.judge(now.minusSeconds(60), BigDecimal("3.74"), now).shouldBeInstanceOf<Verdict.Violating>()
            }
        }

        When("버킷에 표본이 없어 값이 null 이면") {
            Then("0% 정상이 아니라 NO_DATA 판정 불가") {
                condition.judge(now.minusSeconds(60), null, now) shouldBe
                    Verdict.Unknown(now.minusSeconds(60), UnknownReason.NO_DATA)
            }
        }

        When("값은 정상인데 버킷이 5분 전 것이면") {
            Then("수집 · 집계 지연을 정상으로 오판하지 않도록 STALE 판정 불가") {
                val verdict = condition.judge(now.minusSeconds(300), BigDecimal.ZERO, now)
                (verdict as Verdict.Unknown).reason shouldBe UnknownReason.STALE
            }
        }
    }

    Given("fingerprint") {
        val rule = UUID.fromString("5f3c9a2e-1b4d-4e6f-9a8b-0c1d2e3f4a5b")
        val app = UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")

        Then("같은 규칙 + 같은 대상이면 늘 같은 64자 16진수") {
            val a = Fingerprint.of(rule, AlertTarget.Service(app))
            a shouldBe Fingerprint.of(rule, AlertTarget.Service(app))
            a shouldMatch Regex("[0-9a-f]{64}")
        }

        Then("대상 종류가 다르면 UUID 값이 같아도 다른 지문") {
            Fingerprint.of(rule, AlertTarget.Service(app)) shouldNotBe Fingerprint.of(rule, AlertTarget.Agent(app))
        }
    }
})

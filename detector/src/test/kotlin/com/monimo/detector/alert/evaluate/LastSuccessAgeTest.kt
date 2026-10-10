package com.monimo.detector.alert.evaluate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit

// 주기 작업 낡음 게이지 (ADR #54). 막혀서 조용한 것과 정상이라 조용한 것을 가르는 값
class LastSuccessAgeTest : BehaviorSpec({

    val clock = MockClock()
    val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, clock)
    val age = LastSuccessAge(registry, "monimo.detector.evaluation.age", "test")
    fun gauge() = registry.get("monimo.detector.evaluation.age").gauge().value()

    Given("한 번도 성공하지 못한 채 45초가 지나면") {
        clock.add(45, TimeUnit.SECONDS)

        Then("기동 시각부터 세어 45초 — 작업이 막혀 있어도 읽을 때마다 커진다") {
            gauge() shouldBe 45.0
        }
    }

    Given("성공을 적으면") {
        age.markSuccess()
        clock.add(3, TimeUnit.SECONDS)

        Then("그때부터 다시 센다") {
            gauge() shouldBe 3.0
            age.seconds() shouldBe 3.0
        }
    }
})

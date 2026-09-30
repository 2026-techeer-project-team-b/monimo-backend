package com.monimo.detector.alert.evaluate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class BucketValuesTest : BehaviorSpec({

    val t0 = Instant.parse("2026-09-30T10:00:00Z")
    fun at(minute: Long) = t0.plus(Duration.ofMinutes(minute))
    fun row(minute: Long, cnt: Long, c5xx: Long = 0, c4xx: Long = 0, p95: Long = 100) = HealthBucket(at(minute), cnt, c4xx, c5xx, p95)
    fun map(vararg rows: HealthBucket) = rows.associateBy { it.start }

    Given("5XX_RATE (가중 합)") {
        When("트래픽이 다른 두 분을 5분 창으로 보면") {
            // 1분: 10건 중 5건(50%) · 2분: 990건 중 5건(0.5%) → 평균 25% 가 아니라 10/1000 = 1%
            val value = BucketValues.valueAt("5XX_RATE", 300, at(3), map(row(1, 10, c5xx = 5), row(2, 990, c5xx = 5)))

            Then("1분 비율의 평균이 아니라 sum/sum 이다") {
                value shouldBe BigDecimal("1.0000")
            }
        }

        When("판정하는 마지막 1분의 줄이 없으면") {
            val value = BucketValues.valueAt("5XX_RATE", 300, at(3), map(row(0, 100, c5xx = 50), row(1, 100, c5xx = 50)))

            Then("앞의 분이 위반이어도 판정 불가(null) — 적재 전인 분을 정상/위반으로 단정하지 않는다") {
                value shouldBe null
            }
        }

        When("창 밖의 분은") {
            val value = BucketValues.valueAt("5XX_RATE", 60, at(3), map(row(0, 100, c5xx = 100), row(2, 100, c5xx = 0)))

            Then("합치지 않는다 (window 60초 = 판정 버킷 하나)") {
                value shouldBe BigDecimal("0.0000")
            }
        }
    }

    Given("P95_LATENCY") {
        When("여러 분이 있어도") {
            val value = BucketValues.valueAt("P95_LATENCY", 300, at(3), map(row(0, 10, p95 = 9000), row(2, 10, p95 = 120)))

            Then("판정 버킷(마지막 1분)의 p95 만 본다. 평균 내지 않는다") {
                value shouldBe BigDecimal.valueOf(120)
            }
        }
    }

    Given("판정할 버킷 고르기") {
        When("10:03:20 에 settle 30초 · 최대 3분으로 고르면") {
            val ends = BucketValues.settledBucketEnds(Instant.parse("2026-09-30T10:03:20Z"), Duration.ofSeconds(30), Duration.ofMinutes(3))

            Then("완료된 버킷 끝 10:01 · 10:02 만 오래된 순으로. 10:03 은 아직 30초가 안 지났다") {
                ends shouldBe listOf(at(1), at(2))
            }
        }
    }
})

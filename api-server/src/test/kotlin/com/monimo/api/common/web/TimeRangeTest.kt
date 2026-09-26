package com.monimo.api.common.web

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class TimeRangeTest : BehaviorSpec({

    val from = Instant.parse("2026-09-14T10:00:00Z")

    Given("from · to 로 시간 범위를 만들 때") {
        When("from 이 to 보다 앞이면") {
            val range = TimeRange.of(from, from.plusSeconds(3600))

            Then("그대로 만들어진다") {
                range.from shouldBe from
                range.duration shouldBe Duration.ofHours(1)
            }
        }

        When("from 과 to 가 같으면") {
            val ex = shouldThrow<ApiException> { TimeRange.of(from, from) }

            Then("422 UNPROCESSABLE") {
                ex.errorCode shouldBe ErrorCode.UNPROCESSABLE
            }
        }

        When("from 이 to 보다 늦으면") {
            val ex = shouldThrow<ApiException> { TimeRange.of(from, from.minusSeconds(1)) }

            Then("422 UNPROCESSABLE") {
                ex.errorCode shouldBe ErrorCode.UNPROCESSABLE
            }
        }

        When("정확히 7일이면") {
            val range = TimeRange.of(from, from.plus(Duration.ofDays(7)))

            Then("상한 안이라 만들어진다") {
                range.duration shouldBe Duration.ofDays(7)
            }
        }

        When("7일보다 1초 길면") {
            val ex = shouldThrow<ApiException> { TimeRange.of(from, from.plus(Duration.ofDays(7)).plusSeconds(1)) }

            Then("422 TIME_RANGE_TOO_WIDE 와 상한을 알려 준다") {
                ex.errorCode shouldBe ErrorCode.TIME_RANGE_TOO_WIDE
                ex.message shouldBe "시간 범위는 최대 7일입니다."
            }
        }

        When("상한을 넘겨 주면") {
            val range = TimeRange.of(from, from.plus(Duration.ofDays(30)), max = Duration.ofDays(365))

            Then("그 상한으로 판정한다") {
                range.duration shouldBe Duration.ofDays(30)
            }
        }
    }
})

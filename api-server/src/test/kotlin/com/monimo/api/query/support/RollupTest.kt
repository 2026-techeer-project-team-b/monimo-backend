package com.monimo.api.query.support

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RollupTest : BehaviorSpec({

    Given("step 으로 읽을 표 단위를 고를 때") {
        When("경계값을 넣으면") {
            Then("60 미만은 원본") {
                Rollup.of(1) shouldBe Rollup.RAW
                Rollup.of(59) shouldBe Rollup.RAW
            }

            Then("60 이상 3600 미만은 1분 롤업") {
                Rollup.of(60) shouldBe Rollup.MINUTE
                Rollup.of(3599) shouldBe Rollup.MINUTE
            }

            Then("3600 이상은 1시간 롤업") {
                Rollup.of(3600) shouldBe Rollup.HOUR
                Rollup.of(86400) shouldBe Rollup.HOUR
            }

            Then("기본 step 60 은 1분 롤업") {
                Rollup.of(Rollup.DEFAULT_STEP) shouldBe Rollup.MINUTE
            }
        }

        When("0 이하를 넣으면") {
            Then("400 INVALID_REQUEST") {
                shouldThrow<ApiException> { Rollup.of(0) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<ApiException> { Rollup.of(-60) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
    }
})

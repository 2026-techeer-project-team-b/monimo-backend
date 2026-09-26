package com.monimo.api.common.web

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PageLimitTest : BehaviorSpec({

    Given("limit 파라미터를 검사할 때") {
        When("값이 없으면") {
            Then("기본값 50") {
                PageLimit.of(null) shouldBe 50
            }
        }

        When("1 · 500 이면") {
            Then("그대로 쓴다") {
                PageLimit.of(1) shouldBe 1
                PageLimit.of(500) shouldBe 500
            }
        }

        When("0 이나 501 이면") {
            Then("400 INVALID_REQUEST") {
                shouldThrow<ApiException> { PageLimit.of(0) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<ApiException> { PageLimit.of(501) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        When("스캐터처럼 기본값 · 상한을 넘겨 주면") {
            Then("그 값으로 판정한다") {
                PageLimit.of(null, default = 5000, max = 20000) shouldBe 5000
                PageLimit.of(20000, default = 5000, max = 20000) shouldBe 20000
                shouldThrow<ApiException> { PageLimit.of(20001, default = 5000, max = 20000) }
            }
        }
    }
})

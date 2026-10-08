package com.monimo.collector.sampling

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

// 비율 한 벌을 만드는 규칙만 본다. 최댓값 계산과 방어 복사가 전부다 (ADR #53)
class SamplingRatesTest : BehaviorSpec({

    Given("서비스별 비율이 서로 다르면") {
        val rates = SamplingRates.of(
            mapOf("shop-gateway" to 0.01, "shop-order" to 0.1, "shop-payment" to 0.05),
            fallback = 0.01,
        )

        Then("가장 큰 값이 적용된다") {
            rates.applied shouldBe 0.1
        }

        Then("원본은 그대로 남는다 (나중에 서비스별로 쓰게 되면 이 칸을 읽는다)") {
            rates.byService.size shouldBe 3
            rates.byService["shop-gateway"] shouldBe 0.01
        }
    }

    Given("한 줄도 못 읽었으면") {
        val rates = SamplingRates.of(emptyMap(), fallback = 0.01)

        Then("기본값이 적용된다 (빈 값은 0 이나 1 이 되어 둘 다 사고다)") {
            rates.applied shouldBe 0.01
        }

        Then("등록된 서비스가 없다") {
            rates.registered("shop-gateway") shouldBe false
        }
    }

    Given("전부 0.0 이면") {
        Then("적용값도 0.0 이다 (기본값으로 떨어지지 않는다)") {
            SamplingRates.of(mapOf("shop-gateway" to 0.0), fallback = 0.01).applied shouldBe 0.0
        }
    }

    Given("넘긴 Map 을 나중에 고치면") {
        val source = mutableMapOf("shop-gateway" to 0.01)
        val rates = SamplingRates.of(source, fallback = 0.01)

        When("호출자가 값을 넣어도") {
            source["shop-order"] = 1.0

            Then("벌 안은 안 바뀐다 (AtomicReference 로 통째로 갈아끼우므로 불변이어야 한다)") {
                rates.byService.size shouldBe 1
                rates.registered("shop-order") shouldBe false
            }
        }
    }
})

package com.monimo.collector.sampling

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

// 비율 한 벌을 만드는 규칙만 본다. 묶음별 최댓값 계산과 방어 복사가 전부다 (ADR #53 · #72)
class SamplingRatesTest : BehaviorSpec({

    fun shop(ratio: Double) = ServiceRate(system = "shop", ratio = ratio)
    fun monimo(ratio: Double) = ServiceRate(system = "monimo", ratio = ratio)

    Given("같은 묶음 안에서 비율이 서로 다르면") {
        val rates = SamplingRates.of(
            mapOf("shop-gateway" to shop(0.01), "shop-order" to shop(0.1), "shop-payment" to shop(0.05)),
            fallback = 0.01,
        )

        Then("그 묶음의 모든 서비스가 최댓값으로 걸린다 : 선이 하나라 트레이스가 쪼개지지 않는다") {
            rates.appliedFor("shop-gateway") shouldBe 0.1
            rates.appliedFor("shop-order") shouldBe 0.1
            rates.appliedFor("shop-payment") shouldBe 0.1
        }

        Then("원본은 그대로 남는다 (로그와 등록 여부에 쓴다)") {
            rates.byService.size shouldBe 3
            rates.byService["shop-gateway"]?.ratio shouldBe 0.01
        }
    }

    Given("묶음이 둘이면") {
        val rates = SamplingRates.of(
            mapOf(
                "shop-gateway" to shop(0.01),
                "shop-order" to shop(0.01),
                "monimo-api-server" to monimo(0.5),
                "monimo-detector" to monimo(0.1),
            ),
            fallback = 0.02,
        )

        Then("선이 묶음마다 하나씩 생긴다") {
            rates.bySystem shouldBe mapOf("shop" to 0.01, "monimo" to 0.5)
        }

        Then("한 묶음을 올려도 다른 묶음은 안 오른다 : 이 이슈가 풀려는 것이 이것이다") {
            rates.appliedFor("shop-order") shouldBe 0.01
            rates.appliedFor("monimo-detector") shouldBe 0.5
        }

        Then("모르는 이름은 기본 선이다 (남의 묶음 값을 빌리지 않는다)") {
            rates.appliedFor("stranger-app") shouldBe 0.02
        }

        Then("전부 통과가 아니다") {
            rates.passesEverything shouldBe false
        }
    }

    Given("아직 PG 를 못 읽었으면") {
        val rates = SamplingRates.unread(fallback = 0.01)

        Then("기본값이 적용된다 (빈 값은 0 이나 1 이 되어 둘 다 사고다)") {
            rates.appliedFor("shop-gateway") shouldBe 0.01
        }

        Then("등록된 서비스가 없다") {
            rates.registered("shop-gateway") shouldBe false
        }

        Then("읽은 적 없다고 표시된다") {
            rates.loaded shouldBe false
        }
    }

    Given("읽었는데 줄이 0개면") {
        val rates = SamplingRates.of(emptyMap(), fallback = 0.01)

        Then("기본값이 적용되는 것은 같다") {
            rates.appliedFor("shop-gateway") shouldBe 0.01
        }

        Then("묶음별 선이 비어 있다") {
            rates.bySystem.isEmpty() shouldBe true
        }

        Then("그래도 읽었다고 표시된다 : 못 읽은 것과 구분해야 미등록 집계가 켜진다") {
            rates.loaded shouldBe true
        }
    }

    Given("전부 0.0 이면") {
        Then("적용값도 0.0 이다 (기본값으로 떨어지지 않는다)") {
            SamplingRates.of(mapOf("shop-gateway" to shop(0.0)), fallback = 0.01)
                .appliedFor("shop-gateway") shouldBe 0.0
        }
    }

    Given("지름길(요청을 다시 만들지 않는 길)은") {
        Then("모든 묶음과 기본 선이 1.0 이상일 때만 열린다") {
            SamplingRates.of(
                mapOf("shop-gateway" to shop(1.0), "monimo-api-server" to monimo(1.0)),
                fallback = 1.0,
            ).passesEverything shouldBe true
        }

        Then("묶음 하나만 1.0 이면 닫힌다 : 묶음별로 걸러야 한다") {
            SamplingRates.of(
                mapOf("shop-gateway" to shop(0.01), "monimo-api-server" to monimo(1.0)),
                fallback = 1.0,
            ).passesEverything shouldBe false
        }

        Then("기본 선이 1.0 미만이면 닫힌다 : 미등록 서비스가 섞여 올 수 있다") {
            SamplingRates.of(mapOf("shop-gateway" to shop(1.0)), fallback = 0.01)
                .passesEverything shouldBe false
        }

        Then("한 번도 못 읽었으면 기본 선만 본다") {
            SamplingRates.unread(fallback = 1.0).passesEverything shouldBe true
            SamplingRates.unread(fallback = 0.01).passesEverything shouldBe false
        }
    }

    Given("넘긴 Map 을 나중에 고치면") {
        val source = mutableMapOf("shop-gateway" to shop(0.01))
        val rates = SamplingRates.of(source, fallback = 0.01)

        When("호출자가 값을 넣어도") {
            source["monimo-api-server"] = monimo(1.0)

            Then("벌 안은 안 바뀐다 (AtomicReference 로 통째로 갈아끼우므로 불변이어야 한다)") {
                rates.byService.size shouldBe 1
                rates.registered("monimo-api-server") shouldBe false
                rates.bySystem shouldBe mapOf("shop" to 0.01)
            }
        }
    }
})

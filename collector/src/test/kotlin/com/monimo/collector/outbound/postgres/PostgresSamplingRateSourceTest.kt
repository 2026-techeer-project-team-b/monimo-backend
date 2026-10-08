package com.monimo.collector.outbound.postgres

import com.monimo.collector.sampling.SamplingProperties
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

// PG 조회 쪽 동작만 본다. 컨테이너를 띄우지 않고 JdbcTemplate 을 가짜로 바꿔 끼운다:
// 확인하려는 것이 SQL 결과가 아니라 "언제 읽나" 와 "실패하면 무엇을 쓰나" 이기 때문이다 (ADR #53)
class PostgresSamplingRateSourceTest : BehaviorSpec({

    // 부를 때마다 셈을 올리고, 정해 둔 줄을 돌려주거나 터지는 가짜. rows 가 null 이면 PG 가 죽은 것이다
    class FakeJdbc(var rows: Map<String, Double>?) : JdbcTemplate() {
        var calls = 0

        override fun queryForList(sql: String): MutableList<MutableMap<String, Any>> {
            calls++
            val current = rows ?: throw IllegalStateException("PG 가 죽었다")
            return current.map { (name, rate) ->
                mutableMapOf<String, Any>("name" to name, "sampling_rate" to BigDecimal.valueOf(rate))
            }.toMutableList()
        }
    }

    fun sourceOf(jdbc: JdbcTemplate, registry: SimpleMeterRegistry = SimpleMeterRegistry()) =
        PostgresSamplingRateSource(jdbc, SamplingProperties(ratio = 0.01), registry)

    fun refreshCount(registry: SimpleMeterRegistry, outcome: String): Double =
        registry.find("monimo.collector.sampling.refresh").tag("outcome", outcome).counter()?.count() ?: 0.0

    Given("아직 한 번도 안 읽었으면") {
        val jdbc = FakeJdbc(mapOf("shop-order" to 0.1))
        val source = sourceOf(jdbc)

        When("비율을 물어보면") {
            val rates = source.rates()

            Then("yml 기본값으로 돈다 (빈 값을 쓰면 0 이나 1 이 되어 둘 다 사고다)") {
                rates.applied shouldBe 0.01
            }

            Then("PG 를 치지 않는다 : 읽기는 주기 작업이 하고 요청 경로는 들고 있는 값만 준다") {
                jdbc.calls shouldBe 0
            }

            Then("읽은 적 없다는 것을 들고 있다 (0줄 성공과 구분된다)") {
                rates.loaded shouldBe false
            }
        }
    }

    Given("조회는 됐는데 줄이 0개면") {
        val registry = SimpleMeterRegistry()
        val source = sourceOf(FakeJdbc(emptyMap()), registry)

        When("주기 작업이 돌면") {
            source.refresh()

            Then("기본값으로 돌지만 읽었다는 것은 참이다 : 그래야 미등록 집계가 켜진다") {
                source.rates().applied shouldBe 0.01
                source.rates().loaded shouldBe true
            }

            Then("성공으로 센다") {
                refreshCount(registry, "success") shouldBe 1.0
            }
        }
    }

    Given("주기 작업이 한 번 돌면") {
        val jdbc = FakeJdbc(mapOf("shop-gateway" to 0.01, "shop-order" to 0.1))
        val source = sourceOf(jdbc)
        source.refresh()

        Then("서비스별 값 중 최댓값이 적용된다") {
            source.rates().applied shouldBe 0.1
        }

        Then("서비스별 원본도 같이 들고 있다") {
            source.rates().byService["shop-gateway"] shouldBe 0.01
        }

        When("그 뒤 비율을 백 번 물어봐도") {
            val before = jdbc.calls
            repeat(100) { source.rates() }

            Then("PG 를 다시 치지 않는다") {
                jdbc.calls shouldBe before
            }
        }
    }

    Given("읽다가 PG 가 죽으면") {
        val jdbc = FakeJdbc(mapOf("shop-order" to 0.1))
        val registry = SimpleMeterRegistry()
        val source = sourceOf(jdbc, registry)
        source.refresh() // 한 번은 성공시켜 둔다

        When("다음 차례가 터지면") {
            jdbc.rows = null
            source.refresh()

            Then("마지막으로 읽은 값을 그대로 쓴다") {
                source.rates().applied shouldBe 0.1
            }

            Then("실패로 센다 : 로그 한 줄로만 알 수 있으면 아무도 모른다") {
                refreshCount(registry, "failure") shouldBe 1.0
            }
        }

        When("PG 가 돌아오면") {
            jdbc.rows = mapOf("shop-order" to 0.5)
            source.refresh()

            Then("새 값으로 바뀐다") {
                source.rates().applied shouldBe 0.5
            }
        }
    }

    Given("첫 차례부터 터지면") {
        val source = sourceOf(FakeJdbc(null))

        When("주기 작업이 돌아도") {
            source.refresh()

            Then("yml 기본값을 그대로 쓴다") {
                source.rates().applied shouldBe 0.01
            }

            Then("등록된 서비스가 없는 상태다") {
                source.rates().byService.isEmpty() shouldBe true
            }
        }
    }
})

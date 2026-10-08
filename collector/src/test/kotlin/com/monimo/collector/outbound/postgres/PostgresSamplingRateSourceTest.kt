package com.monimo.collector.outbound.postgres

import com.monimo.collector.sampling.SamplingProperties
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Duration

// PG 조회 쪽 동작만 본다. 컨테이너를 띄우지 않고 JdbcTemplate 을 가짜로 바꿔 끼운다:
// 확인하려는 것이 SQL 결과가 아니라 캐시 주기와 실패 때의 선택이기 때문이다 (ADR #53)
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

    fun sourceOf(jdbc: JdbcTemplate, ttl: Duration = Duration.ofSeconds(30)) =
        PostgresSamplingRateSource(jdbc, SamplingProperties(ratio = 0.01, ttl = ttl))

    Given("PG 에 서비스별 줄이 있으면") {
        val jdbc = FakeJdbc(mapOf("shop-gateway" to 0.01, "shop-order" to 0.1))
        val source = sourceOf(jdbc)

        When("처음 물어보면") {
            val rates = source.rates()

            Then("최댓값이 적용된다") {
                rates.applied shouldBe 0.1
            }

            Then("서비스별 원본도 같이 들고 있다") {
                rates.byService["shop-gateway"] shouldBe 0.01
            }
        }

        When("TTL 안에 다시 물어보면") {
            val before = jdbc.calls
            source.rates()

            Then("PG 를 다시 치지 않는다") {
                jdbc.calls shouldBe before
            }
        }
    }

    Given("읽다가 PG 가 죽으면") {
        val jdbc = FakeJdbc(mapOf("shop-order" to 0.1))
        val source = sourceOf(jdbc, ttl = Duration.ZERO) // 매번 다시 읽게 해서 실패를 바로 본다
        source.rates() // 한 번은 성공시켜 둔다

        When("그 뒤 조회가 터지면") {
            jdbc.rows = null
            val rates = source.rates()

            Then("마지막으로 읽은 값을 그대로 쓴다") {
                rates.applied shouldBe 0.1
            }
        }
    }

    Given("한 번도 못 읽으면") {
        val source = sourceOf(FakeJdbc(null), ttl = Duration.ZERO)

        When("물어보면") {
            val rates = source.rates()

            Then("yml 기본값으로 돈다 (빈 값을 쓰면 0 이나 1 이 되어 둘 다 사고다)") {
                rates.applied shouldBe 0.01
            }

            Then("등록된 서비스가 없는 상태다") {
                rates.byService.isEmpty() shouldBe true
            }
        }
    }
})

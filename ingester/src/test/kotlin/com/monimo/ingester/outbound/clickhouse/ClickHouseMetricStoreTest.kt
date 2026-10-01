package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.support.TestInfraConfig
import com.monimo.ingester.transform.MetricRow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

// 진짜 ClickHouse 에 넣고 다시 읽어 확인한다. 변환 규칙은 MetricTranslatorTest 가 보고, 여기서는 표에 제대로 들어가는가만 본다
@SpringBootTest
@Import(TestInfraConfig::class)
class ClickHouseMetricStoreTest(
    store: ClickHouseMetricStore,
    client: Client,
) : BehaviorSpec({

    fun countWhere(table: String, condition: String): Long {
        repeat(20) {
            val value = client.queryAll("SELECT count() FROM monimo.$table WHERE $condition").first().getLong(1)
            if (value > 0) return value
            Thread.sleep(200)
        }
        return client.queryAll("SELECT count() FROM monimo.$table WHERE $condition").first().getLong(1)
    }

    Given("메트릭 두 줄 — 같은 지표, 갈래 다름") {
        val name = "test.metric.${System.nanoTime()}" // 다른 테스트와 섞이지 않게
        val ts = Instant.parse("2026-10-01T03:04:05.678Z")
        val rows = listOf(
            MetricRow("shop-order", "shop-order-7c9d5f-2xk8p", name, mapOf("jvm.memory.pool.name" to "G1 Old Gen"), ts, 300e6),
            MetricRow("shop-order", "shop-order-7c9d5f-2xk8p", name, mapOf("jvm.memory.pool.name" to "G1 Eden Space"), ts, 40e6),
        )

        When("저장하면") {
            store.save(rows)

            Then("metrics_raw 에서 두 줄 다 찾을 수 있다") {
                countWhere("metrics_raw", "metric_name = '$name'") shouldBe 2L
            }

            Then("값 · 시각(초로 잘림) · 꼬리표 · series_hash(UInt64) 가 그대로 들어간다") {
                val row = client.queryAll(
                    "SELECT value, toString(ts), attributes['jvm.memory.pool.name'], toString(series_hash) " +
                        "FROM monimo.metrics_raw WHERE metric_name = '$name' AND value = 300000000 LIMIT 1",
                ).first()
                row.getDouble(1) shouldBe 300e6
                row.getString(2) shouldBe "2026-10-01 03:04:05"
                row.getString(3) shouldBe "G1 Old Gen"
                row.getString(4) shouldBe rows[0].seriesHash.toString()
            }

            Then("MV 가 metrics_1m 에 1분 롤업을 자동으로 만든다 (갈래마다 한 줄)") {
                countWhere("metrics_1m", "metric_name = '$name'") shouldBe 2L
            }
        }
    }
})

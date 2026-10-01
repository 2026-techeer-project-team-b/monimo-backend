package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.support.TestInfraConfig
import com.monimo.ingester.transform.LogRow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

// 진짜 ClickHouse 에 넣고 다시 읽어 확인한다
@SpringBootTest
@Import(TestInfraConfig::class)
class ClickHouseLogStoreTest(
    store: ClickHouseLogStore,
    client: Client,
) : BehaviorSpec({

    fun countWhere(condition: String): Long {
        repeat(20) {
            val value = client.queryAll("SELECT count() FROM monimo.logs WHERE $condition").first().getLong(1)
            if (value > 0) return value
            Thread.sleep(200)
        }
        return client.queryAll("SELECT count() FROM monimo.logs WHERE $condition").first().getLong(1)
    }

    Given("ERROR 로그 한 줄 — 본문에 따옴표와 줄바꿈이 있음") {
        val traceId = "b".repeat(32)
        val row = LogRow(
            traceId = traceId,
            spanId = "0123456789abcdef",
            ts = Instant.parse("2026-10-01T03:04:05.678901Z"),
            serviceName = "shop-payment",
            agentId = "shop-payment-84f6c9-x8d3f",
            logger = "com.monimo.shop.payment.PaymentService",
            thread = "http-nio-8080-exec-7",
            level = "ERROR",
            message = "결제 승인 실패: \"PG_TIMEOUT\"\n재시도 예정",
            attributes = mapOf("http.route" to "/payments", "order.id" to "o-123"),
        )

        When("저장하면") {
            store.save(listOf(row))

            Then("trace_id 로 다시 찾을 수 있다") {
                countWhere("trace_id = '$traceId'") shouldBe 1L
            }

            Then("값이 그대로 들어가고 시각은 밀리초까지 남는다") {
                val saved = client.queryAll(
                    "SELECT service_name, logger, thread, level, message, toString(ts), attributes['order.id'] " +
                        "FROM monimo.logs WHERE trace_id = '$traceId'",
                ).first()
                saved.getString(1) shouldBe "shop-payment"
                saved.getString(2) shouldBe "com.monimo.shop.payment.PaymentService"
                saved.getString(3) shouldBe "http-nio-8080-exec-7"
                saved.getString(4) shouldBe "ERROR"
                saved.getString(5) shouldBe "결제 승인 실패: \"PG_TIMEOUT\"\n재시도 예정" // escape 가 왕복해도 원문 그대로
                saved.getString(6) shouldBe "2026-10-01 03:04:05.678"
                saved.getString(7) shouldBe "o-123"
            }
        }
    }
})

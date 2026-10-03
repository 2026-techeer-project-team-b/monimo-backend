package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.support.TestInfraConfig
import com.monimo.ingester.transform.SpanEvent
import com.monimo.ingester.transform.SpanRow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

// 진짜 ClickHouse 에 넣고 다시 읽어 확인한다. 표는 TestInfraConfig 가 db/clickhouse DDL 로 만들어 둔다.
// 변환 규칙은 SpanTranslatorTest 가 보고, 여기서는 "우리 모델이 표에 제대로 들어가는가" 만 본다
@SpringBootTest
@Import(TestInfraConfig::class)
class ClickHouseSpanStoreTest(
    store: ClickHouseSpanStore,
    client: Client,
) : BehaviorSpec({

    fun rowOf(traceId: String, attributes: Map<String, String> = emptyMap(), events: List<SpanEvent> = emptyList()) = SpanRow(
        traceId = traceId,
        spanId = "0123456789abcdef",
        parentSpanId = "",
        startTime = Instant.parse("2026-09-30T01:02:03.123456789Z"),
        durationNs = 800_000_000L,
        serviceName = "shop-order",
        agentId = "shop-order-7c9d5f-2xk8p",
        spanName = "POST /orders",
        spanKind = "SERVER",
        statusCode = "ERROR",
        httpStatus = 500,
        peerAddress = "",
        peerService = "",
        attributes = attributes,
        events = events,
    )

    // 넣은 것이 보일 때까지 잠깐 기다린다. ClickHouse 는 넣은 직후 조회에 약간 시차가 있을 수 있다
    fun countWhere(condition: String): Long {
        repeat(20) {
            val value = client.queryAll("SELECT count() FROM monimo.spans WHERE $condition").first().getLong(1)
            if (value > 0) return value
            Thread.sleep(200)
        }
        return client.queryAll("SELECT count() FROM monimo.spans WHERE $condition").first().getLong(1)
    }

    Given("스팬 한 줄") {
        val traceId = "a".repeat(32)

        When("저장하면") {
            store.save(listOf(rowOf(traceId)))

            Then("표에서 다시 찾을 수 있다") {
                countWhere("trace_id = '$traceId'") shouldBe 1L
            }

            Then("값이 그대로 들어간다") {
                val row = client.queryAll(
                    "SELECT service_name, agent_id, span_kind, status_code, http_status, duration_ns, " +
                        "toString(start_time) FROM monimo.spans WHERE trace_id = '$traceId'",
                ).first()
                row.getString(1) shouldBe "shop-order"
                row.getString(2) shouldBe "shop-order-7c9d5f-2xk8p"
                row.getString(3) shouldBe "SERVER"
                row.getString(4) shouldBe "ERROR"
                row.getInteger(5) shouldBe 500
                row.getLong(6) shouldBe 800_000_000L
                // 나노초까지 보존되는지 (DateTime64(9))
                row.getString(7) shouldBe "2026-09-30 01:02:03.123456789"
            }
        }
    }

    Given("카나리 표식이 붙은 스팬") {
        val traceId = "b".repeat(32)

        When("저장하면") {
            store.save(listOf(rowOf(traceId, mapOf(SpanRow.CANARY_KEY to "true"))))

            Then("파수꾼이 쓸 조회(mapContains)로 찾힌다") {
                countWhere("mapContains(attributes, '${SpanRow.CANARY_KEY}') AND trace_id = '$traceId'") shouldBe 1L
            }
        }
    }

    Given("여러 줄") {
        val traceId = "c".repeat(32)

        When("한 번에 저장하면") {
            store.save((1..50).map { rowOf(traceId) })

            Then("50줄이 다 들어간다") {
                countWhere("trace_id = '$traceId'") shouldBe 50L
            }
        }

        When("빈 목록을 저장하면") {
            store.save(emptyList())

            Then("아무 일도 일어나지 않는다") {
                countWhere("trace_id = '$traceId'") shouldBe 50L
            }
        }
    }

    Given("따옴표와 줄바꿈이 든 꼬리표") {
        val traceId = "d".repeat(32)

        When("저장하면") {
            store.save(listOf(rowOf(traceId, mapOf("db.statement" to "SELECT \"x\"\nFROM t WHERE a = 'b'"))))

            Then("한 줄 JSON 이 깨지지 않고 값이 그대로 남는다") {
                val value = client.queryAll(
                    "SELECT attributes['db.statement'] FROM monimo.spans WHERE trace_id = '$traceId'",
                ).first().getString(1)
                value shouldBe "SELECT \"x\"\nFROM t WHERE a = 'b'"
            }
        }
    }

    Given("예외 사건 두 개가 든 스팬") {
        val traceId = "e".repeat(32)
        val at = Instant.parse("2026-10-02T01:02:03.123456789Z")
        val events = listOf(
            SpanEvent(at, "exception", mapOf("exception.type" to "TimeoutException", "exception.message" to "카드사 응답 없음")),
            SpanEvent(at.plusMillis(700), "exception", mapOf("exception.type" to "SQLException", "exception.stacktrace" to "SQLException\n\tat Repo.save(Repo.kt:10)")),
        )

        When("저장하면") {
            store.save(listOf(rowOf(traceId, events = events)))

            Then("Nested 세 배열이 같은 길이 2 로 들어간다") {
                countWhere("trace_id = '$traceId'") shouldBe 1L
                val row = client.queryAll(
                    "SELECT length(events.ts), length(events.name), length(events.attributes) FROM monimo.spans WHERE trace_id = '$traceId'",
                ).first()
                row.getLong(1) shouldBe 2L
                row.getLong(2) shouldBe 2L
                row.getLong(3) shouldBe 2L
            }

            Then("같은 인덱스끼리 한 사건이다 — 첫 사건의 종류 · 둘째 사건의 스택트레이스 · 시각이 나노초까지") {
                val row = client.queryAll(
                    "SELECT events.name[1], events.attributes[1]['exception.type'], events.attributes[2]['exception.stacktrace'], toString(events.ts[1]) " +
                        "FROM monimo.spans WHERE trace_id = '$traceId'",
                ).first()
                row.getString(1) shouldBe "exception"
                row.getString(2) shouldBe "TimeoutException"
                row.getString(3) shouldBe "SQLException\n\tat Repo.save(Repo.kt:10)"
                row.getString(4) shouldBe "2026-10-02 01:02:03.123456789"
            }

            Then("예외가 있는 스팬만 고를 수 있다 — 조회(/errors)가 쓰는 방식") {
                client.queryAll("SELECT count() FROM monimo.spans WHERE trace_id = '$traceId' AND has(events.name, 'exception')")
                    .first().getLong(1) shouldBe 1L
            }
        }
    }

    Given("사건이 없는 스팬") {
        val traceId = "f".repeat(32)

        When("저장하면") {
            store.save(listOf(rowOf(traceId)))

            Then("events 는 빈 배열이고 예외 필터에 걸리지 않는다") {
                countWhere("trace_id = '$traceId'") shouldBe 1L
                client.queryAll("SELECT length(events.name) FROM monimo.spans WHERE trace_id = '$traceId'").first().getLong(1) shouldBe 0L
                client.queryAll("SELECT count() FROM monimo.spans WHERE trace_id = '$traceId' AND has(events.name, 'exception')").first().getLong(1) shouldBe 0L
            }
        }
    }
})

package com.monimo.api.query.trace

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SpanTreeTest : BehaviorSpec({

    fun span(id: String, parent: String?, service: String, startNs: Long, durationNs: Long = 100) = SpanRecord(
        spanId = id, parentSpanId = parent, serviceName = service, agentKey = "$service-pod", spanName = "op-$id",
        spanKind = "SERVER", startNs = startNs, durationNs = durationNs, statusCode = "UNSET", httpStatus = null,
        attributes = emptyMap(), events = emptyList(),
    )

    Given("부모 스팬이 모두 있는 트레이스") {
        // gateway → order → (payment, db). 일부러 순서를 섞어 넣는다
        val records = listOf(
            span("db", "order", "shop-order", 300),
            span("pay", "order", "shop-payment", 200),
            span("gw", null, "shop-gateway", 0, 1_000),
            span("order", "gw", "shop-order", 100),
        )

        When("트리로 조립하면") {
            val trace = SpanTree.assemble("t1", records)

            Then("루트 스팬이 맨 위에 있고 parent_span_id 는 null") {
                trace.root.spanId shouldBe "gw"
                trace.root.parentSpanId shouldBe null
                trace.spanCount shouldBe 4
            }

            Then("자식은 시작 시각 순으로 붙는다") {
                val order = trace.root.children.single()
                order.spanId shouldBe "order"
                order.children.map { it.spanId } shouldBe listOf("pay", "db")
            }

            Then("services 는 처음 나온 순서") {
                trace.services shouldBe listOf("shop-gateway", "shop-order", "shop-payment")
            }
        }
    }

    Given("루트 스팬이 아직 도착하지 않았고 그 아래가 한 덩어리인 트레이스") {
        val records = listOf(
            span("order", "gw", "shop-order", 100),
            span("pay", "order", "shop-payment", 200),
        )

        When("트리로 조립하면") {
            val trace = SpanTree.assemble("t2", records)

            Then("부모 없는 스팬 하나를 그대로 루트로 쓴다 (누락된 구간을 만들지 않는다)") {
                trace.root.spanId shouldBe "order"
                trace.root.parentSpanId shouldBe "gw"
                trace.root.children.map { it.spanId } shouldBe listOf("pay")
            }
        }
    }

    Given("부모 스팬이 없는 스팬이 둘인 트레이스") {
        // order 스팬이 빠져서 db · pay 가 둘 다 부모를 못 찾는다
        val records = listOf(
            span("pay", "order", "shop-payment", 200, 300),
            span("db", "order", "shop-order", 150, 50),
        )

        When("트리로 조립하면") {
            val trace = SpanTree.assemble("t3", records)

            Then("누락된 구간 자리를 루트로 두고 둘을 나란히 붙인다") {
                trace.root.spanId shouldBe ""
                trace.root.spanName shouldBe SpanTree.MISSING_SPAN_NAME
                trace.root.children.map { it.spanId } shouldBe listOf("db", "pay")
            }

            Then("누락된 구간은 아래 스팬들의 시작 ~ 끝을 덮고, span_count 에는 세지 않는다") {
                trace.root.startTime shouldBe SpanTree.format(150)
                trace.root.durationNs shouldBe 350
                trace.spanCount shouldBe 2
            }
        }
    }

    Given("시각 표기") {
        When("나노초를 글자로 바꾸면") {
            Then("소수점 아래 9자리 고정이다 (화면이 글자로 정렬)") {
                SpanTree.format(1_790_670_016_152_378_252) shouldBe "2026-09-29T08:20:16.152378252Z"
                SpanTree.format(1_790_670_016_100_000_000) shouldBe "2026-09-29T08:20:16.100000000Z"
            }
        }
    }
})

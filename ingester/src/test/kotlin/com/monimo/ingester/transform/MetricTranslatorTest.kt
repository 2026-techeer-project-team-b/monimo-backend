package com.monimo.ingester.transform

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Gauge
import io.opentelemetry.proto.metrics.v1.Histogram
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.metrics.v1.Sum
import io.opentelemetry.proto.resource.v1.Resource
import java.time.Instant

// 변환 규칙만 보는 단위 테스트. 스프링도 컨테이너도 안 띄운다
class MetricTranslatorTest : BehaviorSpec({

    fun attr(key: String, value: String): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    val at = 1_700_000_000_123_456_789L // 나노초. 초로 자르면 1_700_000_000

    fun requestOf(vararg metrics: Metric.Builder): ExportMetricsServiceRequest =
        ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(
                ResourceMetrics.newBuilder()
                    .setResource(
                        Resource.newBuilder()
                            .addAttributes(attr("service.name", "shop-order"))
                            .addAttributes(attr("service.instance.id", "shop-order-7c9d5f-2xk8p")),
                    )
                    .addScopeMetrics(ScopeMetrics.newBuilder().apply { metrics.forEach { addMetrics(it) } }),
            )
            .build()

    fun doublePoint(value: Double, vararg attrs: KeyValue) =
        NumberDataPoint.newBuilder().setTimeUnixNano(at).setAsDouble(value).addAllAttributes(attrs.toList())

    Given("Gauge 하나에 갈래(attributes)가 다른 포인트 둘") {
        val metric = Metric.newBuilder().setName("jvm.memory.used").setGauge(
            Gauge.newBuilder()
                .addDataPoints(doublePoint(300e6, attr("jvm.memory.type", "heap"), attr("jvm.memory.pool.name", "G1 Old Gen")))
                .addDataPoints(doublePoint(40e6, attr("jvm.memory.pool.name", "G1 Eden Space"), attr("jvm.memory.type", "heap"))),
        )

        When("우리 모델로 옮기면") {
            val rows = MetricTranslator.toRows(requestOf(metric))

            Then("포인트 1개 = 1줄 (long 형식)") {
                rows shouldHaveSize 2
                rows.map { it.metricName }.toSet() shouldBe setOf("jvm.memory.used")
                rows.map { it.value } shouldContainExactly listOf(300e6, 40e6)
            }

            Then("서비스 이름 · 파드 식별자는 resource 에서 온다") {
                rows[0].serviceName shouldBe "shop-order"
                rows[0].agentId shouldBe "shop-order-7c9d5f-2xk8p"
            }

            Then("측정 시각은 나노초를 Instant 로 바꾼 것이다 (초로 자르는 건 CH 에 넣을 때)") {
                rows[0].ts shouldBe Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
            }

            Then("갈래가 다르면 series_hash 가 다르다") {
                rows[0].seriesHash shouldNotBe rows[1].seriesHash
            }
        }
    }

    Given("같은 꼬리표를 순서만 다르게 넣은 두 Map") {
        val a = mapOf("jvm.memory.type" to "heap", "jvm.memory.pool.name" to "G1 Old Gen")
        val b = linkedMapOf("jvm.memory.pool.name" to "G1 Old Gen", "jvm.memory.type" to "heap")

        Then("series_hash 는 같다 — 키를 정렬해서 해시하기 때문") {
            MetricRow.seriesHashOf(a) shouldBe MetricRow.seriesHashOf(b)
        }

        Then("꼬리표가 없으면 빈 글자의 해시로, 항상 같은 값이다") {
            MetricRow.seriesHashOf(emptyMap()) shouldBe MetricRow.seriesHashOf(emptyMap())
        }
    }

    Given("Sum 에 정수 값(as_int) 포인트") {
        val metric = Metric.newBuilder().setName("jvm.thread.count").setSum(
            Sum.newBuilder().addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(at).setAsInt(42)),
        )

        When("옮기면") {
            val row = MetricTranslator.toRows(requestOf(metric)).single()

            Then("Double 로 들어간다 (CH value 는 Float64)") {
                row.value shouldBe 42.0
            }
        }
    }

    Given("값이 비어 있는 포인트 (as_double 도 as_int 도 없음)") {
        val metric = Metric.newBuilder().setName("empty").setGauge(
            Gauge.newBuilder().addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(at)),
        )

        Then("줄을 만들지 않는다 — 0.0 을 지어내지 않는다") {
            MetricTranslator.toRows(requestOf(metric)) shouldHaveSize 0
        }
    }

    Given("히스토그램 포인트 (count · sum · min · max 전부 있음)") {
        val metric = Metric.newBuilder().setName("jvm.gc.duration").setHistogram(
            Histogram.newBuilder().addDataPoints(
                HistogramDataPoint.newBuilder()
                    .setTimeUnixNano(at)
                    .setCount(3).setSum(0.041).setMin(0.004).setMax(0.025)
                    .addBucketCounts(1).addBucketCounts(2).addExplicitBounds(0.01)
                    .addAttributes(attr("jvm.gc.name", "G1 Young Generation")),
            ),
        )

        When("옮기면") {
            val rows = MetricTranslator.toRows(requestOf(metric))

            Then("이름에 접미를 붙여 4줄이 된다. 버킷은 버린다") {
                rows.associate { it.metricName to it.value } shouldBe mapOf(
                    "jvm.gc.duration.count" to 3.0,
                    "jvm.gc.duration.sum" to 0.041,
                    "jvm.gc.duration.min" to 0.004,
                    "jvm.gc.duration.max" to 0.025,
                )
            }

            Then("4줄은 같은 갈래라 꼬리표와 series_hash 가 같다") {
                rows.map { it.seriesHash }.toSet() shouldHaveSize 1
                rows[0].attributes shouldBe mapOf("jvm.gc.name" to "G1 Young Generation")
            }
        }
    }

    Given("히스토그램 포인트에 min · max 가 없음 (optional)") {
        val metric = Metric.newBuilder().setName("http.server.request.duration").setHistogram(
            Histogram.newBuilder().addDataPoints(HistogramDataPoint.newBuilder().setTimeUnixNano(at).setCount(10).setSum(2.5)),
        )

        Then("count · sum 2줄만 만든다 — 없는 min · max 를 0 으로 지어내지 않는다") {
            MetricTranslator.toRows(requestOf(metric)).map { it.metricName } shouldContainExactly
                listOf("http.server.request.duration.count", "http.server.request.duration.sum")
        }
    }
})

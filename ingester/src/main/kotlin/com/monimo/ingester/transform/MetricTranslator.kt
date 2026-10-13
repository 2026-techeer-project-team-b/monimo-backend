package com.monimo.ingester.transform

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint

// OTLP 메트릭 요청을 우리 모델(MetricRow) 목록으로 옮긴다. 스프링도 ClickHouse 도 모르는 순수 코드다.
//
// OTel 메트릭은 종류가 다섯이다. 숫자 하나짜리(Gauge · Sum)는 포인트 1개 = 1줄로 바로 옮기고,
// 분포(Histogram · ExponentialHistogram · Summary)는 숫자가 여럿이라 이름에 접미를 붙여 여러 줄로 편다.
//
// 값은 바꾸지 않는다 (ADR #38 "OTel 모양 그대로"). 누적 → 구간 값 계산은 직전 값을 기억해야 해서 상태가 생기고,
// 적재 처리기가 여러 대면 그 기억이 갈라진다. 대신 OTel 이 보내 준 종류 · 누적 여부를 버리지 않고
// 줄마다 (metricType · isCounter) 로 남겨, 읽는 쪽이 어느 줄에 차이 계산을 걸지 알게 한다 (#189, ADR #68).
object MetricTranslator {

    fun toRows(request: ExportMetricsServiceRequest): List<MetricRow> =
        request.resourceMetricsList.flatMap { resourceMetrics ->
            val origin = resourceMetrics.resource.origin() // 서비스 이름 · 파드 식별자는 바깥에 한 번만 있다
            resourceMetrics.scopeMetricsList.flatMap { scopeMetrics ->
                scopeMetrics.metricsList.flatMap { metric -> toRows(metric, origin) }
            }
        }

    // 종류별로 가른다. dataCase = 다섯 중 어느 것이 들어 있는지 (protobuf oneof)
    private fun toRows(metric: Metric, origin: Origin): List<MetricRow> = when (metric.dataCase) {
        // Gauge 는 "지금 얼마" 라 총합일 수 없다
        Metric.DataCase.GAUGE -> metric.gauge.dataPointsList.mapNotNull {
            numberRow(metric.name, MetricType.GAUGE, isCounter = false, it, origin)
        }
        // Sum 이 총합인 조건은 둘 다여야 한다 : 누적(cumulative) 이고 단조(is_monotonic).
        //   델타 Sum 은 이미 "이 구간에 얼마" 라 뺄 것이 없고,
        //   단조가 아닌 Sum(UpDownCounter, 예: jvm.memory.used · jvm.thread.count) 은 오르내리는 현재값이다
        Metric.DataCase.SUM -> metric.sum.let { sum ->
            val counter = sum.isMonotonic && sum.aggregationTemporality.isCumulative()
            sum.dataPointsList.mapNotNull { numberRow(metric.name, MetricType.SUM, counter, it, origin) }
        }

        // 분포는 누적이면 count · sum 이 켜진 뒤로 쌓인 총합이다 (GC 횟수 · GC 시간 합). min · max 는 총합이 아니다
        Metric.DataCase.HISTOGRAM -> metric.histogram.let { h ->
            h.dataPointsList.flatMap { p ->
                distributionRows(
                    metric.name, MetricType.HISTOGRAM, h.aggregationTemporality.isCumulative(),
                    p.attributesList, p.timeUnixNano, origin,
                    count = p.count, sum = if (p.hasSum()) p.sum else null,
                    min = if (p.hasMin()) p.min else null, max = if (p.hasMax()) p.max else null,
                )
            }
        }
        Metric.DataCase.EXPONENTIAL_HISTOGRAM -> metric.exponentialHistogram.let { h ->
            h.dataPointsList.flatMap { p ->
                distributionRows(
                    metric.name, MetricType.EXPONENTIAL_HISTOGRAM, h.aggregationTemporality.isCumulative(),
                    p.attributesList, p.timeUnixNano, origin,
                    count = p.count, sum = if (p.hasSum()) p.sum else null,
                    min = if (p.hasMin()) p.min else null, max = if (p.hasMax()) p.max else null,
                )
            }
        }
        // Summary 는 temporality 칸이 없고 규약상 항상 누적이다
        Metric.DataCase.SUMMARY -> metric.summary.dataPointsList.flatMap { p ->
            distributionRows(
                metric.name, MetricType.SUMMARY, cumulative = true,
                p.attributesList, p.timeUnixNano, origin,
                count = p.count, sum = p.sum, min = null, max = null,
            )
        }
        // 종류가 안 적혀 있으면 넣을 숫자가 없다
        Metric.DataCase.DATA_NOT_SET, null -> emptyList()
    }

    // 적혀 있지 않은(UNSPECIFIED) temporality 는 누적으로 치지 않는다 : 모르는데 빼면 값이 사라지는 쪽으로 틀린다
    private fun AggregationTemporality.isCumulative(): Boolean =
        this == AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE

    // 숫자 하나짜리 포인트. 값이 as_double 또는 as_int 중 하나로 온다 (oneof). 둘 다 없으면 줄을 만들지 않는다
    private fun numberRow(name: String, type: MetricType, isCounter: Boolean, point: NumberDataPoint, origin: Origin): MetricRow? {
        val value = when (point.valueCase) {
            NumberDataPoint.ValueCase.AS_DOUBLE -> point.asDouble
            NumberDataPoint.ValueCase.AS_INT -> point.asInt.toDouble()
            else -> return null
        }
        return row(name, type, isCounter, point.attributesList, point.timeUnixNano, origin, value)
    }

    // 분포 포인트 → <이름>.count · <이름>.sum (+ 있으면 .min · .max). 버킷은 버린다.
    // 인스펙터는 평균 · 최대만 그린다. 분포가 필요해지면 그때 표를 따로 판다
    private fun distributionRows(
        name: String, type: MetricType, cumulative: Boolean,
        attributes: List<KeyValue>, timeUnixNano: Long, origin: Origin,
        count: Long, sum: Double?, min: Double?, max: Double?,
    ): List<MetricRow> = buildList {
        add(row("$name.count", type, cumulative, attributes, timeUnixNano, origin, count.toDouble()))
        sum?.let { add(row("$name.sum", type, cumulative, attributes, timeUnixNano, origin, it)) }
        min?.let { add(row("$name.min", type, false, attributes, timeUnixNano, origin, it)) }
        max?.let { add(row("$name.max", type, false, attributes, timeUnixNano, origin, it)) }
    }

    private fun row(
        name: String, type: MetricType, isCounter: Boolean,
        attributes: List<KeyValue>, timeUnixNano: Long, origin: Origin, value: Double,
    ) = MetricRow(
        serviceName = origin.serviceName,
        agentId = origin.agentId,
        metricName = name,
        metricType = type,
        isCounter = isCounter,
        attributes = attributes.toStringMap(),
        ts = timeUnixNano.nanosToInstant(),
        value = value,
    )
}

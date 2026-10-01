package com.monimo.ingester.transform

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint

// OTLP 메트릭 요청을 우리 모델(MetricRow) 목록으로 옮긴다. 스프링도 ClickHouse 도 모르는 순수 코드다.
//
// OTel 메트릭은 종류가 다섯이다. 숫자 하나짜리(Gauge · Sum)는 포인트 1개 = 1줄로 바로 옮기고,
// 분포(Histogram · ExponentialHistogram · Summary)는 숫자가 여럿이라 이름에 접미를 붙여 여러 줄로 편다.
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
        Metric.DataCase.GAUGE -> metric.gauge.dataPointsList.mapNotNull { numberRow(metric.name, it, origin) }
        // Sum 의 누적/델타(aggregation_temporality)는 바꾸지 않고 그대로 넣는다 (ADR #38 "OTel 모양 그대로").
        // 누적 → 델타 계산은 직전 값을 기억해야 해서 상태가 생긴다. 조회 쪽이 runningDifference 로 할 수 있다
        Metric.DataCase.SUM -> metric.sum.dataPointsList.mapNotNull { numberRow(metric.name, it, origin) }

        Metric.DataCase.HISTOGRAM -> metric.histogram.dataPointsList.flatMap { p ->
            distributionRows(
                metric.name, p.attributesList, p.timeUnixNano, origin,
                count = p.count, sum = if (p.hasSum()) p.sum else null,
                min = if (p.hasMin()) p.min else null, max = if (p.hasMax()) p.max else null,
            )
        }
        Metric.DataCase.EXPONENTIAL_HISTOGRAM -> metric.exponentialHistogram.dataPointsList.flatMap { p ->
            distributionRows(
                metric.name, p.attributesList, p.timeUnixNano, origin,
                count = p.count, sum = if (p.hasSum()) p.sum else null,
                min = if (p.hasMin()) p.min else null, max = if (p.hasMax()) p.max else null,
            )
        }
        Metric.DataCase.SUMMARY -> metric.summary.dataPointsList.flatMap { p ->
            distributionRows(metric.name, p.attributesList, p.timeUnixNano, origin, count = p.count, sum = p.sum, min = null, max = null)
        }
        // 종류가 안 적혀 있으면 넣을 숫자가 없다
        Metric.DataCase.DATA_NOT_SET, null -> emptyList()
    }

    // 숫자 하나짜리 포인트. 값이 as_double 또는 as_int 중 하나로 온다 (oneof). 둘 다 없으면 줄을 만들지 않는다
    private fun numberRow(name: String, point: NumberDataPoint, origin: Origin): MetricRow? {
        val value = when (point.valueCase) {
            NumberDataPoint.ValueCase.AS_DOUBLE -> point.asDouble
            NumberDataPoint.ValueCase.AS_INT -> point.asInt.toDouble()
            else -> return null
        }
        return row(name, point.attributesList, point.timeUnixNano, origin, value)
    }

    // 분포 포인트 → <이름>.count · <이름>.sum (+ 있으면 .min · .max). 버킷은 버린다.
    // 인스펙터는 평균 · 최대만 그린다. 분포가 필요해지면 그때 표를 따로 판다
    private fun distributionRows(
        name: String, attributes: List<KeyValue>, timeUnixNano: Long, origin: Origin,
        count: Long, sum: Double?, min: Double?, max: Double?,
    ): List<MetricRow> = buildList {
        add(row("$name.count", attributes, timeUnixNano, origin, count.toDouble()))
        sum?.let { add(row("$name.sum", attributes, timeUnixNano, origin, it)) }
        min?.let { add(row("$name.min", attributes, timeUnixNano, origin, it)) }
        max?.let { add(row("$name.max", attributes, timeUnixNano, origin, it)) }
    }

    private fun row(name: String, attributes: List<KeyValue>, timeUnixNano: Long, origin: Origin, value: Double) =
        MetricRow(
            serviceName = origin.serviceName,
            agentId = origin.agentId,
            metricName = name,
            attributes = attributes.toStringMap(),
            ts = timeUnixNano.nanosToInstant(),
            value = value,
        )
}

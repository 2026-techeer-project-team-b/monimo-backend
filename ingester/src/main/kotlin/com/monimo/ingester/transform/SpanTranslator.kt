package com.monimo.ingester.transform

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status

// OTLP 요청을 우리 모델(SpanRow) 목록으로 옮긴다.
//
// **스프링도 ClickHouse 도 모르는 순수 코드다.** 어노테이션이 없고 객체도 안 만든다(object).
// 그래서 컨테이너 없이 테스트가 돌고, 저장소를 바꿔도 이 파일은 그대로다.
// OTLP 값 다루기(꼬리표 → Map, 16진수, 나노초, resource 에서 서비스 · 파드)는 세 변환기가 같이 쓰는 OtlpValues.kt 에 있다.
object SpanTranslator {

    // 카나리 표식 (ADR #41). 수집기의 SamplingProperties.canaryMarker 와 같은 글자여야 한다
    private const val CANARY_MARKER = "monimon=canary"

    // HTTP 응답 코드가 들어오는 꼬리표. 안정판 이름이 먼저, 옛 이름이 뒤
    private val HTTP_STATUS_KEYS = listOf("http.response.status_code", "http.status_code")

    // 호출 대상 주소를 만들 때 쓰는 꼬리표
    private const val SERVER_ADDRESS_KEY = "server.address"
    private const val SERVER_PORT_KEY = "server.port"
    private const val PEER_SERVICE_KEY = "peer.service"

    fun toRows(request: ExportTraceServiceRequest): List<SpanRow> =
        // 3겹 구조(resource → scope → span)를 편다. 서비스 이름과 파드 식별자는 resource 에 한 번만 있고
        // 스팬마다 반복되지 않으므로, 바깥에서 한 번 꺼내 안쪽 스팬들에 나눠 준다
        request.resourceSpansList.flatMap { resourceSpans ->
            val origin = resourceSpans.resource.origin()
            resourceSpans.scopeSpansList.flatMap { scopeSpans ->
                scopeSpans.spansList.map { span -> toRow(span, origin) }
            }
        }

    private fun toRow(span: Span, origin: Origin): SpanRow {
        val attributes = span.attributesList.toStringMap()
        return SpanRow(
            traceId = span.traceId.toHex(),
            spanId = span.spanId.toHex(),
            parentSpanId = span.parentSpanId.toHex(), // 루트면 빈 ByteString → 빈 글자가 된다
            startTime = span.startTimeUnixNano.nanosToInstant(),
            // 끝난 시각이 시작보다 앞설 수는 없지만, 이상한 데이터가 와도 음수가 저장되지 않게 막는다(CH 는 UInt64)
            durationNs = (span.endTimeUnixNano - span.startTimeUnixNano).coerceAtLeast(0),
            serviceName = origin.serviceName,
            agentId = origin.agentId,
            spanName = span.name,
            spanKind = span.kind.toRowValue(),
            statusCode = span.status.code.toRowValue(),
            httpStatus = HTTP_STATUS_KEYS.firstNotNullOfOrNull { attributes[it]?.toIntOrNull() } ?: 0,
            peerAddress = peerAddressOf(attributes),
            peerService = attributes[PEER_SERVICE_KEY] ?: "",
            attributes = attributes + canaryMark(span),
            // 사건 목록. 다른 컬럼은 값 하나인데 이것만 1:다라 #58 에서 빼고 진행했다가 놓쳤다 (#79)
            events = span.eventsList.map { it.toEvent() },
        )
    }

    private fun Span.Event.toEvent(): SpanEvent = SpanEvent(
        ts = timeUnixNano.nanosToInstant(),
        name = name,
        attributes = attributesList.toStringMap(),
    )

    // 카나리면 표식을 꼬리표에 하나 더한다. 아니면 아무것도 안 더한다(빈 map)
    private fun canaryMark(span: Span): Map<String, String> =
        if (isCanary(span.traceState)) mapOf(SpanRow.CANARY_KEY to "true") else emptyMap()

    // trace_state 는 "키=값,키=값" 목록이다. 통째로 contains 하면 monimon=canaryx 같은 것도 걸리므로 항목 단위로 본다
    private fun isCanary(traceState: String): Boolean =
        traceState.isNotEmpty() && traceState.split(',').any { it.trim() == CANARY_MARKER }

    // "shop-order:8080" 모양으로 만든다. 포트가 없으면 주소만, 주소도 없으면 빈 글자
    private fun peerAddressOf(attributes: Map<String, String>): String {
        val address = attributes[SERVER_ADDRESS_KEY] ?: return ""
        val port = attributes[SERVER_PORT_KEY] ?: return address
        return "$address:$port"
    }

    // CH Enum8 과 같은 글자로 맞춘다. OTel 이 SPAN_KIND_SERVER 처럼 보내므로 접두를 떼어 낸다
    private fun Span.SpanKind.toRowValue(): String = when (this) {
        Span.SpanKind.SPAN_KIND_SERVER -> "SERVER"
        Span.SpanKind.SPAN_KIND_CLIENT -> "CLIENT"
        Span.SpanKind.SPAN_KIND_PRODUCER -> "PRODUCER"
        Span.SpanKind.SPAN_KIND_CONSUMER -> "CONSUMER"
        else -> "INTERNAL" // UNSPECIFIED · INTERNAL · 모르는 값
    }

    private fun Status.StatusCode.toRowValue(): String = when (this) {
        Status.StatusCode.STATUS_CODE_OK -> "OK"
        Status.StatusCode.STATUS_CODE_ERROR -> "ERROR"
        else -> "UNSET"
    }
}

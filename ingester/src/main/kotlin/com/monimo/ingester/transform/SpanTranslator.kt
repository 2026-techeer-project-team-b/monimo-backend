package com.monimo.ingester.transform

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import java.time.Instant

// OTLP 요청을 우리 모델(SpanRow) 목록으로 옮긴다.
//
// **스프링도 ClickHouse 도 모르는 순수 코드다.** 어노테이션이 없고 객체도 안 만든다(object).
// 그래서 컨테이너 없이 테스트가 돌고, 저장소를 바꿔도 이 파일은 그대로다.
object SpanTranslator {

    // resource 속성에서 파드 식별자를 찾는 순서. 위에서부터 있는 것을 쓴다.
    // service.instance.id 가 OTel 표준이고, 쿠버네티스에서는 보통 파드 이름이 들어간다
    private val AGENT_ID_KEYS = listOf("service.instance.id", "k8s.pod.name", "host.name")

    private const val SERVICE_NAME_KEY = "service.name"
    private const val UNKNOWN_SERVICE = "unknown_service" // 에이전트가 이름을 안 넣으면 OTel 이 이렇게 보낸다

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
            val resource = resourceSpans.resource.attributesList.toStringMap()
            val serviceName = resource[SERVICE_NAME_KEY] ?: UNKNOWN_SERVICE
            val agentId = AGENT_ID_KEYS.firstNotNullOfOrNull { resource[it] } ?: ""

            resourceSpans.scopeSpansList.flatMap { scopeSpans ->
                scopeSpans.spansList.map { span -> toRow(span, serviceName, agentId) }
            }
        }

    private fun toRow(span: Span, serviceName: String, agentId: String): SpanRow {
        val attributes = span.attributesList.toStringMap()
        return SpanRow(
            traceId = span.traceId.toHex(),
            spanId = span.spanId.toHex(),
            parentSpanId = span.parentSpanId.toHex(), // 루트면 빈 ByteString → 빈 글자가 된다
            startTime = span.startTimeUnixNano.toInstant(),
            // 끝난 시각이 시작보다 앞설 수는 없지만, 이상한 데이터가 와도 음수가 저장되지 않게 막는다(CH 는 UInt64)
            durationNs = (span.endTimeUnixNano - span.startTimeUnixNano).coerceAtLeast(0),
            serviceName = serviceName,
            agentId = agentId,
            spanName = span.name,
            spanKind = span.kind.toRowValue(),
            statusCode = span.status.code.toRowValue(),
            httpStatus = HTTP_STATUS_KEYS.firstNotNullOfOrNull { attributes[it]?.toIntOrNull() } ?: 0,
            peerAddress = peerAddressOf(attributes),
            peerService = attributes[PEER_SERVICE_KEY] ?: "",
            attributes = attributes + canaryMark(span),
        )
    }

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

    // OTLP 꼬리표는 값이 여러 타입(문자 · 숫자 · 참거짓 · 배열)이다. CH attributes 는 Map(String, String) 이라 글자로 맞춘다
    private fun List<KeyValue>.toStringMap(): Map<String, String> =
        associate { it.key to it.value.asString() }

    private fun AnyValue.asString(): String = when (valueCase) {
        AnyValue.ValueCase.STRING_VALUE -> stringValue
        AnyValue.ValueCase.BOOL_VALUE -> boolValue.toString()
        AnyValue.ValueCase.INT_VALUE -> intValue.toString()
        AnyValue.ValueCase.DOUBLE_VALUE -> doubleValue.toString()
        // 배열 · 중첩 구조는 protobuf 가 준 글자 모양 그대로 넣는다. 화면에서 그대로 보여 주기만 한다
        else -> toString().trim()
    }

    // 16바이트 trace ID → 소문자 16진수 32글자. 빈 값이면 빈 글자
    private fun ByteString.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private fun Long.toInstant(): Instant =
        Instant.ofEpochSecond(this / 1_000_000_000L, this % 1_000_000_000L)

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

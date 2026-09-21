package com.monimo.common

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import kotlin.test.Test
import kotlin.test.assertEquals

// opentelemetry-proto 연결 확인용. 실제 쇼핑몰 데이터로 하는 왕복 테스트는 개발환경 9단계에서 만든다.
class OtlpProtobufRoundTripTest {

    @Test
    fun `OTLP 트레이스 요청은 protobuf 바이트로 바꿨다가 다시 읽어도 같다`() {
        val span = Span.newBuilder()
            .setName("GET /orders")
            .setTraceId(ByteString.copyFrom(ByteArray(16) { 1 }))
            .setSpanId(ByteString.copyFrom(ByteArray(8) { 2 }))
        val request = ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
            .build()

        val restored = ExportTraceServiceRequest.parseFrom(request.toByteArray())

        assertEquals(request, restored)
    }
}

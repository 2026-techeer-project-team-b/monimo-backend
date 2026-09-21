package com.monimo.common

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span

// opentelemetry-proto 연결 확인용. 실제 쇼핑몰 데이터로 하는 왕복 테스트는 개발환경 9단계에서 만든다.
class OtlpProtobufRoundTripTest : BehaviorSpec({

    Given("span 하나가 든 OTLP 트레이스 요청") {
        val span = Span.newBuilder()
            .setName("GET /orders")
            .setTraceId(ByteString.copyFrom(ByteArray(16) { 1 }))
            .setSpanId(ByteString.copyFrom(ByteArray(8) { 2 }))
        val request = ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
            .build()

        When("protobuf 바이트로 바꿨다가 다시 읽으면") {
            val restored = ExportTraceServiceRequest.parseFrom(request.toByteArray())

            Then("원래 요청과 같다") {
                restored shouldBe request
            }
        }
    }
})

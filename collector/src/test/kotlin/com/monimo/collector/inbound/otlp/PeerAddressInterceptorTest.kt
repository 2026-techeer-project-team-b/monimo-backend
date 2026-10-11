package com.monimo.collector.inbound.otlp

import io.grpc.Attributes
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import java.net.InetSocketAddress
import java.net.SocketAddress

// 가로채기가 "연결에서 읽은 주소를 Context 에 담는다" 를 고정한다. gRPC 서버를 띄우지 않고
// 호출 하나를 손으로 만들어 통과시킨다 (서버를 띄우는 쪽은 OtlpGrpcReceiverTest 가 본다).
//
// 주소를 못 읽는 경우를 여기서만 볼 수 있다 : 진짜 TCP 서버로는 상대 주소가 늘 읽히므로
// "실험 API 가 값을 안 줄 때" 를 재현할 방법이 없다
class PeerAddressInterceptorTest : BehaviorSpec({

    // 호출 하나. 우리가 보는 것은 attributes 뿐이라 나머지는 아무 일도 하지 않는다
    fun callWith(attributes: Attributes) = object : ServerCall<ExportTraceServiceRequest, ExportTraceServiceResponse>() {
        override fun request(numMessages: Int) = Unit
        override fun sendHeaders(headers: Metadata) = Unit
        override fun sendMessage(message: ExportTraceServiceResponse) = Unit
        override fun close(status: Status, trailers: Metadata) = Unit
        override fun isCancelled(): Boolean = false
        override fun getMethodDescriptor() = TraceServiceGrpc.getExportMethod()
        override fun getAttributes(): Attributes = attributes
    }

    // 호출을 받자마자 Context 에서 주소를 꺼내 적어 두는 가짜 서비스. 서비스 구현이 하는 것과 같은 일이다
    class Recorder : ServerCallHandler<ExportTraceServiceRequest, ExportTraceServiceResponse> {
        var seen: String? = "아직 안 불렸다"
        override fun startCall(
            call: ServerCall<ExportTraceServiceRequest, ExportTraceServiceResponse>,
            headers: Metadata,
        ): ServerCall.Listener<ExportTraceServiceRequest> {
            seen = currentPeerAddress()
            return object : ServerCall.Listener<ExportTraceServiceRequest>() {}
        }
    }

    fun attributesWith(address: SocketAddress): Attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, address).build()

    Given("가로채기를 붙인 수집기") {
        When("TCP 연결로 호출이 들어오면") {
            val interceptor = PeerAddressInterceptor(SimpleMeterRegistry())
            val recorder = Recorder()
            interceptor.interceptCall(callWith(attributesWith(InetSocketAddress("172.19.0.12", 55001))), Metadata(), recorder)

            Then("서비스 구현이 Context 에서 그 주소를 꺼낼 수 있다") {
                recorder.seen shouldBe "172.19.0.12"
            }

            Then("못 읽은 횟수는 오르지 않는다") {
                interceptor.unknownCount() shouldBe 0.0
            }
        }

        When("주소를 읽을 수 없는 호출이 들어오면") {
            val interceptor = PeerAddressInterceptor(SimpleMeterRegistry())
            val recorder = Recorder()
            // 상대 주소 속성이 아예 없다. 전송 수단이 바뀌거나 실험 API 가 값을 안 줄 때 이렇게 된다
            interceptor.interceptCall(callWith(Attributes.EMPTY), Metadata(), recorder)

            Then("서비스 구현은 null 을 본다 : 메모를 안 붙인다") {
                recorder.seen shouldBe null
            }

            Then("신호를 거절하지 않고 그대로 처리한다 : 눈금만 하나 오른다") {
                interceptor.unknownCount() shouldBe 1.0
            }
        }
    }
})

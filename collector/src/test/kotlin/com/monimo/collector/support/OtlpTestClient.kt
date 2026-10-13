package com.monimo.collector.support

import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import java.io.File

// 에이전트 역할을 하는 시험용 gRPC 손님 (#159). TLS 로 붙는 쪽과 평문으로 붙는 쪽을 같은 모양으로 만든다
object OtlpTestClient {

    // 평문 손님. TLS 를 끈 수집기(기본값)에 붙는 지금까지의 방식
    fun plaintext(port: Int): ManagedChannel =
        ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()

    // TLS 손님. trust = 수집기 신분증의 도장을 검사할 기관 인증서. client = 낼 신분증(없으면 null : "신분증 없는 손님")
    fun tls(port: Int, trust: File, clientCertificate: File? = null, clientKey: File? = null): ManagedChannel {
        val credentials = TlsChannelCredentials.newBuilder()
            .trustManager(trust)
            .apply { if (clientCertificate != null && clientKey != null) keyManager(clientCertificate, clientKey) }
            .build()
        // 수집기 신분증에 적힌 이름(localhost)으로 붙어야 손님 쪽 이름 검사가 통과한다
        return Grpc.newChannelBuilder("localhost:$port", credentials).build()
    }

    fun oneSpan(name: String = "GET /orders"): ExportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder().addScopeSpans(
                    ScopeSpans.newBuilder().addSpans(Span.newBuilder().setName(name)),
                ),
            )
            .build()

    // 스팬 하나를 보낸다. 연결은 첫 호출 때 맺어지므로 악수 실패도 여기서 드러난다
    fun export(channel: ManagedChannel, request: ExportTraceServiceRequest = oneSpan()) {
        TraceServiceGrpc.newBlockingStub(channel).export(request)
    }

    // 보내 보고 거절당한 상태 코드를 돌려준다. 통과하면 null
    fun rejectedWith(channel: ManagedChannel): io.grpc.Status.Code? =
        try {
            export(channel)
            null
        } catch (e: StatusRuntimeException) {
            e.status.code
        }
}

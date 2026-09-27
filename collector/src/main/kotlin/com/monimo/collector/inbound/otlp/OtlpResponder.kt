package com.monimo.collector.inbound.otlp

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

private val log = LoggerFactory.getLogger("com.monimo.collector.inbound.otlp.OtlpResponder")

// Kafka 발행이 끝난 뒤에 gRPC 응답을 돌려준다. 세 신호 서비스가 같은 규칙을 쓰게 한 곳에 모았다.
// 성공 → 빈 성공 응답 · 실패 → UNAVAILABLE (에이전트가 같은 배치를 다시 보낸다. 못 넣은 데이터가 조용히 사라지지 않는다)
fun <T> StreamObserver<T>.respondAfter(sent: CompletableFuture<*>, response: T, signal: String) {
    sent.whenComplete { _, error ->
        if (error == null) {
            onNext(response)
            onCompleted()
        } else {
            log.warn("OTLP {} 를 Kafka raw 에 못 넣음. 에이전트에 재시도 요청", signal, error)
            onError(Status.UNAVAILABLE.withDescription("raw 발행 실패").withCause(error).asRuntimeException())
        }
    }
}

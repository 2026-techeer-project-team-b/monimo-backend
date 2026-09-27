package com.monimo.collector.inbound.otlp // 들어오는 문(inbound) 폴더 — OTLP 수신 쪽

import io.grpc.Status // gRPC 상태 코드 모음 (OK · UNAVAILABLE ...)
import io.grpc.stub.StreamObserver // 에이전트에게 답장을 써 보내는 통로
import org.slf4j.LoggerFactory // 로그 찍는 도구를 만들어 주는 곳
import java.util.concurrent.CompletableFuture // 나중에 결과가 들어올 상자

// 이 파일에는 class 가 없다(함수만 있는 파일). 그래서 로거 이름을 클래스 대신 글자로 직접 적는다.
// private = 이 파일 안에서만 보인다
private val log = LoggerFactory.getLogger("com.monimo.collector.inbound.otlp.OtlpResponder")

// Kafka 발행이 끝난 뒤에 gRPC 응답을 돌려준다. 세 신호 서비스가 같은 규칙을 쓰게 한 곳에 모았다.
// 성공 → 빈 성공 응답 · 실패 → UNAVAILABLE (에이전트가 같은 배치를 다시 보낸다. 못 넣은 데이터가 조용히 사라지지 않는다)
//
// <T> = 응답 타입을 비워 둔 칸 (트레이스 · 메트릭 · 로그 응답이 서로 다른 타입이라 하나로 못 박지 않는다)
// StreamObserver<T>. = 남의 클래스에 우리 함수를 덧붙인다(확장 함수). 점 앞의 객체가 함수 안에서 this 가 된다
// sent = Kafka 결과 상자 · response = 성공 때 보낼 응답 · signal = 로그에 찍을 이름
fun <T> StreamObserver<T>.respondAfter(sent: CompletableFuture<*>, response: T, signal: String) {
    // 지금 실행하지 않는다. "상자가 채워지면 아래를 실행해라" 라고 예약만 하고 함수는 바로 끝난다.
    // 몇 ms 뒤 Kafka 응답이 오면 상자가 값 2개를 건넨다 — 성공이면 (결과, null) · 실패면 (null, 예외)
    // _ = 안 쓰는 값(결과)  ·  error = 예외
    sent.whenComplete { _, error ->
        if (error == null) { // 예외가 없다 = Kafka 저장 성공
            onNext(response) // 에이전트에게 성공 응답을 써 보낸다 (this. 생략 — 확장 함수라서)
            onCompleted() // "답장 끝" 을 알린다. 빠뜨리면 에이전트가 계속 기다린다
        } else { // 예외가 있다 = Kafka 저장 실패
            // {} 자리에 signal 이 들어간다. 마지막 error 는 예외라서 스택 트레이스까지 찍힌다
            log.warn("OTLP {} 를 Kafka raw 에 못 넣음. 에이전트에 재시도 요청", signal, error)
            // 성공 대신 실패를 보낸다. UNAVAILABLE = "지금 처리 못 한다" → 에이전트가 같은 데이터를 다시 보낸다
            onError(Status.UNAVAILABLE.withDescription("raw 발행 실패").withCause(error).asRuntimeException())
        }
    }
}

package com.monimo.collector.inbound.otlp

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 들어온 gRPC 호출마다 연결 상대의 주소를 읽어 Context 에 담는다. 세 서비스(트레이스 · 지표 · 로그)
// 전부에 걸린다 : 서버를 만들 때 서비스마다 붙이지 않고 `ServerBuilder.intercept` 로 한 번 건다.
//
// 가로채기에서 읽는 이유 : 주소는 요청 하나가 아니라 **연결** 에 딸린 값이다. 서비스 구현 안에서는
// 그 연결을 들여다볼 통로가 없다. gRPC 가 그 값을 `ServerCall` 의 속성으로 내주므로 호출이 시작될 때
// 한 번 꺼내 Context 에 담고, 서비스 구현은 Context 에서 받아 쓴다.
//
// 못 읽을 수 있다 : 꺼내는 데 쓰는 `Grpc.TRANSPORT_ATTR_REMOTE_ADDR` 는 grpc-java 가 **실험 API**
// (`@ExperimentalApi`)로 표시해 둔 상수다. 전송 수단이 바뀌면(유닉스 소켓 · 테스트용 인메모리 전송)
// 값이 없거나 주소라고 할 수 없는 타입이 온다. 그때는 메모를 안 붙이고 눈금만 올린다.
// **신호를 거절하지는 않는다** : 화면 칸 하나 때문에 데이터를 버리는 일은 없다 (ADR `#63`)
@Component
class PeerAddressInterceptor(registry: MeterRegistry) : ServerInterceptor {

    // 주소를 못 읽은 호출 수. 미리 등록해 한 번도 없었을 때도 0 으로 보이게 한다 (OtlpReceiveCounter 와 같은 방식).
    // 조용히 빈칸이 되는 일을 알아채려고 둔다 : 안 세면 "주소가 왜 비었는지" 를 물을 자리가 없다
    private val unknown: Counter = Counter.builder(METRIC)
        .description("수집기가 gRPC 연결에서 상대 주소를 읽지 못한 호출 수")
        .baseUnit("calls")
        .register(registry)

    fun unknownCount(): Double = unknown.count()

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val socketAddress = call.attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
        val address = peerAddressOf(socketAddress)
        if (address == null) {
            unknown.increment()
            // DEBUG 다. 전송 수단 때문에 늘 못 읽는 환경이면 WARN 이 매 호출마다 쌓인다
            log.debug("gRPC 연결에서 상대 주소를 읽지 못했다: {}", socketAddress)
            return next.startCall(call, headers) // Context 는 손대지 않는다. 꺼내는 쪽은 기본값 null 을 본다
        }

        // 이 호출을 처리하는 동안만 값이 살아 있다. 호출이 끝나면 gRPC 가 치운다
        return Contexts.interceptCall(Context.current().withValue(PEER_ADDRESS, address), call, headers, next)
    }

    companion object {
        const val METRIC = "monimo.collector.otlp.peer_address.unknown"
        private val log = LoggerFactory.getLogger(PeerAddressInterceptor::class.java)
    }
}

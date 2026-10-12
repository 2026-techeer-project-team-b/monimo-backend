package com.monimo.collector.inbound.otlp

import io.grpc.Attributes
import io.grpc.Grpc
import io.grpc.ServerTransportFilter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

// 지금 붙어 있는 에이전트 (#159 · ADR #64, 7절 묶음 5 "붙어 있는 수를 센다"). 게이지 둘을 낸다.
//
//   monimo.collector.otlp.connections  연결 수. 준비되면 +1, 끊기면 -1
//   monimo.collector.otlp.agents       주소가 다른 상대의 수 (= 에이전트 수). 같은 주소의 연결이 몇 개든 하나로 센다
//
// 둘인 이유 : 에이전트 하나가 연결을 여럿 연다. 신호(트레이스 · 지표 · 로그)마다 따로 열고, 로그는 보낼 로그가
// 생겨야 연다(실측 : 쇼핑몰 넷이 기동 직후 12, 수집기를 재시작하면 8). 그래서 연결 수로는 "넷이 다 붙었나" 를 바로
// 못 읽고, 주소를 묶은 에이전트 수가 그 질문에 답한다. 쇼핑몰이 넷이면 agents 가 4 여야 하고 3 이면 하나가 못 붙은 것이다.
//
// 왜 거절 수가 아니라 붙어 있는 수인가 : 신분증이 없거나 틀린 손님은 TLS 악수 단계에서 끊긴다. 이 필터는
// 악수가 **끝난 뒤에만** 불리므로(transportReady) 끊긴 손님은 여기까지 오지 않는다. 즉 거절은 셀 수 없다.
// 거절을 세려면 Netty 안쪽을 열어야 하고 거기는 안정된 방법이 없다(확인 완료). 대신 "몇이 붙어 있나" 를 보면
// 요구 수준을 올렸을 때 누가 떨어졌는지 바로 드러난다.
//
// 이 자리(ServerTransportFilter · Grpc.TRANSPORT_ATTR_SSL_SESSION · Grpc.TRANSPORT_ATTR_REMOTE_ADDR)는 grpc-java 가
// 실험 API 로 표시해 뒀다. 바뀌면 다시 본다 (ADR #64 되돌림 ⑤). `#149` 의 주소 읽기와 같은 성격이다.
//
// Counter 가 아니라 Gauge 다 : 몇 번 붙었나가 아니라 **지금 몇 개인가** 를 본다.
// 태그 auth 로 셋을 가른다. optional 단계에서 "넷이 붙었는데 신분증은 셋만 냈다" 를 이 태그로 알 수 있다 :
//   plaintext = 평문 연결 (TLS 꺼짐)
//   tls       = TLS 로 왔는데 신분증은 안 냈다 (optional 일 때만 생긴다)
//   mtls      = 신분증을 냈고 도장이 맞았다
@Component
class OtlpConnectionGauge(registry: MeterRegistry) : ServerTransportFilter() {

    enum class Auth(val tag: String) { PLAINTEXT("plaintext"), TLS("tls"), MTLS("mtls") }

    // Gauge 는 값을 약한 참조로 들고 있어서 여기서 세게 붙잡아 둬야 한다
    private val connections: Map<Auth, AtomicInteger> = Auth.entries.associateWith { auth ->
        AtomicInteger(0).also { count ->
            Gauge.builder(CONNECTIONS, count) { it.get().toDouble() }
                .description("지금 붙어 있는 OTLP gRPC 연결 수")
                .baseUnit("connections")
                .tag("auth", auth.tag)
                .register(registry)
        }
    }

    // 주소 → 그 주소의 연결 수. 0 이 되면 지운다. 게이지 값은 주소의 수다
    private val peers: Map<Auth, ConcurrentHashMap<String, Int>> = Auth.entries.associateWith { auth ->
        ConcurrentHashMap<String, Int>().also { byAddress ->
            Gauge.builder(AGENTS, byAddress) { it.size.toDouble() }
                .description("지금 붙어 있는 OTLP gRPC 상대(주소가 다른 에이전트)의 수")
                .baseUnit("agents")
                .tag("auth", auth.tag)
                .register(registry)
        }
    }

    fun connections(auth: Auth): Int = connections.getValue(auth).get()
    fun agents(auth: Auth): Int = peers.getValue(auth).size

    // 악수가 끝나 연결이 준비됐다. 어떤 연결인지 가려서 세고, 끊길 때 같은 칸에서 빼려고 속성에 적어 둔다
    override fun transportReady(transportAttrs: Attributes): Attributes {
        val auth = authOf(transportAttrs.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
        connections.getValue(auth).incrementAndGet()
        // 주소를 못 읽는 전송 수단(인메모리 등)이면 에이전트 수에는 안 들어간다. 연결 수에는 들어간다
        val address = peerAddressOf(transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR))
        if (address != null) peers.getValue(auth).merge(address, 1, Int::plus)
        return transportAttrs.toBuilder().set(AUTH, auth).set(ADDRESS, address).build()
    }

    // 끊겼다. transportReady 가 돌려준 속성이 그대로 온다
    override fun transportTerminated(transportAttrs: Attributes) {
        val auth = transportAttrs.get(AUTH) ?: return // 준비되기 전에 끊긴 연결은 센 적이 없다
        connections.getValue(auth).decrementAndGet()
        val address = transportAttrs.get(ADDRESS) ?: return
        peers.getValue(auth).computeIfPresent(address) { _, count -> if (count <= 1) null else count - 1 }
    }

    private fun authOf(session: SSLSession?): Auth {
        if (session == null) return Auth.PLAINTEXT
        return try {
            // 손님이 신분증을 냈고 도장이 맞으면 여기 있다. 안 냈으면 예외다 (optional 에서 허용된 경우)
            if (session.peerCertificates.isNotEmpty()) Auth.MTLS else Auth.TLS
        } catch (e: SSLPeerUnverifiedException) {
            Auth.TLS
        }
    }

    companion object {
        const val CONNECTIONS = "monimo.collector.otlp.connections"
        const val AGENTS = "monimo.collector.otlp.agents"
        private val AUTH: Attributes.Key<Auth> = Attributes.Key.create("monimo-otlp-auth")
        private val ADDRESS: Attributes.Key<String?> = Attributes.Key.create("monimo-otlp-address")
    }
}

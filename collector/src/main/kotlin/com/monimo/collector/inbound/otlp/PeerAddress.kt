package com.monimo.collector.inbound.otlp

import io.grpc.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

// 연결 상대(에이전트가 붙은 파드)의 주소를 글자로 만드는 자리. 스프링을 모르는 순수 함수들이다.
//
// 주소는 데이터 안에 없다. 에이전트 기본값은 호스트 이름과 CPU 종류만 보내고 주소는 안 보낸다.
// 즉 주소는 "데이터의 성질" 이 아니라 "연결의 성질" 이라, 연결을 들고 있는 수집기만 알 수 있다
// (ADR `#63`). 그래서 여기서 읽어 Kafka 봉투 메모로 적재 처리기까지 옮긴다.

// 가로채기(PeerAddressInterceptor)가 넣고 서비스 구현이 꺼내는 통로.
// gRPC Context 는 그 호출을 처리하는 동안 따라다니는 값 보관함이다. ThreadLocal 과 비슷하지만
// gRPC 가 스레드를 바꿔도 같이 옮겨 준다.
//
// 기본값이 null 이라, 가로채기를 못 지나온 호출(단위 테스트 등)에서도 터지지 않고 "모른다" 가 된다
internal val PEER_ADDRESS: Context.Key<String?> = Context.key("monimo.peer.address")

// 지금 처리 중인 호출의 상대 주소. 모르면 null
internal fun currentPeerAddress(): String? = PEER_ADDRESS.get()

// gRPC 가 준 소켓 주소를 명부에 넣을 글자로 바꾼다. 못 바꾸면 null 이고, null 이면 메모를 안 붙인다.
//
// SocketAddress 는 추상 타입이다. TCP 면 InetSocketAddress 지만 유닉스 소켓 · 테스트 전송 수단이면
// 다른 타입이라 주소라고 할 것이 없다. 이름이 안 풀린 주소도 같다. 그때는 null 로 둔다
// (PG 의 ip 칸은 INET 이라 주소가 아닌 글자를 못 받는다)
internal fun peerAddressOf(address: SocketAddress?): String? =
    (address as? InetSocketAddress)?.address?.toAddressText()

// 주소 하나를 PG INET 칸에 넣어도 되는 글자로 만든다. 두 가지를 손본다.
//
// ① IPv4 를 IPv6 칸에 담은 꼴(`::ffff:172.19.0.11`)이면 IPv4 네 토막으로 되돌린다. 로컬 compose 에서
//    실제로 이 꼴로 온다. 떼는 이유는 저장이 실패해서가 아니다 : PG INET 은 이 글자를 **조용히 받아**
//    주소 종류를 IPv6 로 기록한다(실측 2026-10-12). 실패하면 바로 알아채는데 조용히 들어가면 화면에
//    괴상한 값이 그대로 보이고 주소 종류까지 틀린다.
//    글자가 아니라 **바이트 16개**를 보고 가른다 : 자바가 그 주소를 `::ffff:a.b.c.d` 로도,
//    `0:0:0:0:0:ffff:...` 로도 적을 수 있어 글자 모양에 기대면 플랫폼에 따라 새는 경우가 생긴다
// ② IPv6 링크로컬 주소에 붙는 영역 꼬리(`%eth0` · `%2` 같은 zone index)를 뗀다. PG INET 은 그 꼴을
//    거절하는데, 거절당하면 등록이 실패로 세어지고 그 파드는 캐시에 안 들어가 메시지마다 다시 시도한다(경고 홍수)
internal fun InetAddress.toAddressText(): String {
    val bytes = address
    if (bytes.size == IPV6_BYTES && bytes.isIpv4Mapped()) {
        // 뒤 4바이트가 원래 IPv4 주소다. 부호 없는 값으로 읽어 점으로 잇는다
        return bytes.copyOfRange(IPV6_BYTES - 4, IPV6_BYTES).joinToString(".") { (it.toInt() and 0xFF).toString() }
    }
    return hostAddress.substringBefore('%')
}

// IPv4 매핑 IPv6 = 앞 10바이트가 0, 그 다음 2바이트가 0xFF 0xFF (RFC 4291 §2.5.5.2)
private fun ByteArray.isIpv4Mapped(): Boolean =
    (0 until 10).all { this[it] == ZERO } && this[10] == ALL_ONES && this[11] == ALL_ONES

private const val IPV6_BYTES = 16
private const val ZERO: Byte = 0
private const val ALL_ONES: Byte = -1 // 0xFF 를 Byte 로 읽은 값

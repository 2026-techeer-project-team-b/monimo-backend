package com.monimo.collector.inbound.otlp

import io.grpc.TlsServerCredentials
import org.springframework.boot.context.properties.ConfigurationProperties

// OTLP gRPC 수신 설정. 포트 하나와 TLS 한 묶음.
//
// TLS 는 에이전트 인증(mTLS)을 위한 것이다 (#159 · ADR #64). 수집기 포트가 평문이고 인증이 없어서
// 거기 닿는 누구나 가짜 신호를 넣을 수 있었고, 카나리 표식을 붙이면 샘플링까지 비껴갔다.
// 기본값은 꺼짐(none, 평문)이다 : 받은 사람이 아무것도 안 해도 지금처럼 돌아야 한다
@ConfigurationProperties("monimo.collector.otlp.grpc")
data class OtlpGrpcProperties(
    val port: Int = 4317,
    val tls: Tls = Tls(),
) {
    data class Tls(
        // 손님(에이전트) 신분증을 얼마나 요구하나. 두 단계 전환(research 7절 묶음 6)은 이 값 하나를 바꾸는 것으로 끝난다 :
        // none(평문) → optional(TLS 로 받되 신분증은 있으면 확인하고 없어도 받는다 : 빠뜨린 쪽을 찾는 단계) → require(반드시)
        val clientAuth: ClientAuth = ClientAuth.NONE,
        // 수집기가 읽는 파일 셋. optional · require 일 때만 읽고 셋 다 있어야 한다. none 이면 무시한다.
        // 기동할 때 한 번 읽는다. 다시 읽는 장치는 없다 : 갱신은 파일을 바꾸고 재시작하는 것이다 (7절 묶음 3)
        val serverCertificate: String? = null, // 수집기 신분증 (PEM)
        val serverKey: String? = null, // 수집기 열쇠 (PKCS8 PEM)
        val caCertificate: String? = null, // 기관 인증서. 손님 신분증의 도장을 이것으로 검사한다
    )

    // 글자 셋이 grpc-java 의 ClientAuth 셋에 그대로 간다 (공식 javadoc : NONE · OPTIONAL · REQUIRE).
    // 모르는 글자는 스프링이 묶을 때 실패해 기동이 멈춘다. 조용히 기본값으로 떨어지지 않는다
    enum class ClientAuth {
        // TLS 자체를 켜지 않는다. 서버만 TLS 이고 손님 검사는 안 하는 상태(grpc 의 ClientAuth.NONE 인 TLS)는 만들지 않는다 :
        // 이 작업의 목표가 암호화가 아니라 "누가 보냈나" 라서 그 중간 상태에 쓸 데가 없다
        NONE,
        OPTIONAL,
        REQUIRE,
        ;

        fun toGrpc(): TlsServerCredentials.ClientAuth = when (this) {
            NONE -> TlsServerCredentials.ClientAuth.NONE
            OPTIONAL -> TlsServerCredentials.ClientAuth.OPTIONAL
            REQUIRE -> TlsServerCredentials.ClientAuth.REQUIRE
        }
    }
}

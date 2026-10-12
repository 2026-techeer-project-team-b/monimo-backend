package com.monimo.collector.inbound.otlp

import io.grpc.InsecureServerCredentials
import io.grpc.ServerCredentials
import io.grpc.TlsServerCredentials
import java.io.File

// 설정을 gRPC 서버 자격증명으로 바꾼다 (#159 · ADR #64).
//
// none 이면 평문(InsecureServerCredentials), 아니면 TLS(TlsServerCredentials) 다. 두 API 는 실험 표시가 없다.
//
// 켜 놓고 파일을 못 읽으면 여기서 예외를 던져 기동을 멈춘다. 조용히 평문으로 뜨면
// "켠 줄 알았는데 안 켜진" 상태가 되고 그게 가장 나쁘다 (8절 ⑥). 파일은 기동할 때 한 번 읽는다.
//
// 손님 신분증은 **도장만** 본다 : 기관 인증서를 trustManager 로 주는 것이 전부고, 신분증에 적힌 이름은
// 검사하지 않는다 (7절 묶음 2). 이름까지 보려면 ADR #64 되돌림 ② 로 올린다
internal fun serverCredentialsOf(tls: OtlpGrpcProperties.Tls): ServerCredentials {
    if (tls.clientAuth == OtlpGrpcProperties.ClientAuth.NONE) return InsecureServerCredentials.create()

    val files = mapOf(
        "수집기 신분증(server-certificate)" to tls.serverCertificate,
        "수집기 열쇠(server-key)" to tls.serverKey,
        "기관 인증서(ca-certificate)" to tls.caCertificate,
    )
    val missing = files.filter { (_, path) -> path.isNullOrBlank() || !File(path).canRead() }
    check(missing.isEmpty()) {
        val names = missing.map { (name, path) -> "$name=${path ?: "(비어 있음)"}" }
        "OTLP gRPC TLS 를 ${tls.clientAuth} 로 켰는데 읽을 수 없는 파일이 있다 : $names. " +
            "평문으로 대신 뜨지 않는다. monimo.collector.otlp.grpc.tls.* 설정과 마운트를 확인하라"
    }

    return TlsServerCredentials.newBuilder()
        .keyManager(File(tls.serverCertificate!!), File(tls.serverKey!!))
        .trustManager(File(tls.caCertificate!!))
        .clientAuth(tls.clientAuth.toGrpc())
        .build()
}

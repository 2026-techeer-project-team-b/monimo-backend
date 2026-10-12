package com.monimo.collector.inbound.otlp

import io.grpc.Grpc
import io.grpc.Server
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

// OTLP gRPC 서버. 스프링 컨텍스트와 같이 켜지고 꺼진다.
// 스프링 웹(8081)과 별개 포트(4317)에서 돈다. OTel Java Agent 기본 전송 방식(gRPC)을 그대로 받기 위해서다.
//
// 자격증명을 받는 꼴(Grpc.newServerBuilderForPort)로 만든다 (#159 · ADR #64). 설정이 none 이면 평문,
// optional · require 면 TLS 로 받고 손님 신분증의 도장을 본다. 어느 쪽이든 `#149` 의 주소 가로채기는 그대로 걸린다 :
// 이 수집기가 TLS 를 직접 끝내므로 상대 주소가 입구 주소로 바뀌지 않는다 (research 2.5 · 7절 "이 선택이 만드는 것")
@Component
@EnableConfigurationProperties(OtlpGrpcProperties::class)
class OtlpGrpcServer(
    private val properties: OtlpGrpcProperties,
    private val traceService: OtlpTraceService,
    private val metricsService: OtlpMetricsService,
    private val logsService: OtlpLogsService,
    private val peerAddressInterceptor: PeerAddressInterceptor, // 연결 상대 주소를 Context 에 담는다 (`#149`)
    private val connectionGauge: OtlpConnectionGauge, // 지금 붙어 있는 연결 수 (`#159`)
) : SmartLifecycle {

    private var server: Server? = null

    // 실제로 잡은 포트. 설정이 0 이면 여기서 확인한다.
    val port: Int
        get() = server?.port ?: -1

    override fun start() {
        val tls = properties.tls
        // 켜 놓고 파일을 못 읽으면 여기서 예외가 나고 스프링 기동이 멈춘다. 평문으로 대신 뜨지 않는다
        val credentials = serverCredentialsOf(tls)
        val started = Grpc.newServerBuilderForPort(properties.port, credentials)
            // 서비스마다 붙이지 않고 서버에 한 번 건다. 뒤에 서비스를 더해도 자동으로 걸린다
            .intercept(peerAddressInterceptor)
            .addTransportFilter(connectionGauge)
            .addService(traceService)
            .addService(metricsService)
            .addService(logsService)
            .build()
            .start()
        server = started
        // 돌고 있는 수집기의 실제 상태를 확인할 자리. HealthCheckFilter 가 거를 목록을 찍는 것과 같은 이유 (`#92`)
        if (tls.clientAuth == OtlpGrpcProperties.ClientAuth.NONE) {
            log.info("OTLP gRPC 수신 시작: 포트 {} · TLS 꺼짐(평문) · 손님 신분증 요구 none", started.port)
        } else {
            log.info(
                "OTLP gRPC 수신 시작: 포트 {} · TLS 켜짐 · 손님 신분증 요구 {} · 수집기 신분증 {} · 수집기 열쇠 {} · 기관 인증서 {}" +
                    " (파일은 기동 때 한 번 읽었다. 바꾸면 재시작)",
                started.port, tls.clientAuth, tls.serverCertificate, tls.serverKey, tls.caCertificate,
            )
        }
    }

    override fun stop() {
        server?.let {
            it.shutdown()
            if (!it.awaitTermination(5, TimeUnit.SECONDS)) it.shutdownNow()
            log.info("OTLP gRPC 수신 종료")
        }
        server = null
    }

    override fun isRunning(): Boolean = server?.let { !it.isShutdown } ?: false

    // 웹 서버보다 늦게 켜고 먼저 끈다
    override fun getPhase(): Int = Int.MAX_VALUE - 1

    private companion object {
        val log = LoggerFactory.getLogger(OtlpGrpcServer::class.java)
    }
}

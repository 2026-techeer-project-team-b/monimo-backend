package com.monimo.collector.inbound.otlp

import io.grpc.Server
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

// OTLP gRPC 서버. 스프링 컨텍스트와 같이 켜지고 꺼진다.
// 스프링 웹(8081)과 별개 포트(4317)에서 돈다. OTel Java Agent 기본 전송 방식(gRPC)을 그대로 받기 위해서다.
@Component
@EnableConfigurationProperties(OtlpGrpcProperties::class)
class OtlpGrpcServer(
    private val properties: OtlpGrpcProperties,
    private val traceService: OtlpTraceService,
    private val metricsService: OtlpMetricsService,
    private val logsService: OtlpLogsService,
    private val peerAddressInterceptor: PeerAddressInterceptor, // 연결 상대 주소를 Context 에 담는다 (`#149`)
) : SmartLifecycle {

    private var server: Server? = null

    // 실제로 잡은 포트. 설정이 0 이면 여기서 확인한다.
    val port: Int
        get() = server?.port ?: -1

    override fun start() {
        val started = ServerBuilder.forPort(properties.port)
            // 서비스마다 붙이지 않고 서버에 한 번 건다. 뒤에 서비스를 더해도 자동으로 걸린다
            .intercept(peerAddressInterceptor)
            .addService(traceService)
            .addService(metricsService)
            .addService(logsService)
            .build()
            .start()
        server = started
        log.info("OTLP gRPC 수신 시작: 포트 {}", started.port)
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

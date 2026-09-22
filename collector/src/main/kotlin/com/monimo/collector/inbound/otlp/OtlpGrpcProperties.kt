package com.monimo.collector.inbound.otlp

import org.springframework.boot.context.properties.ConfigurationProperties

// OTLP gRPC 수신 설정. port = 0 이면 빈 포트를 아무거나 잡는다 (테스트용).
@ConfigurationProperties("monimo.collector.otlp.grpc")
data class OtlpGrpcProperties(
    val port: Int = 4317,
)

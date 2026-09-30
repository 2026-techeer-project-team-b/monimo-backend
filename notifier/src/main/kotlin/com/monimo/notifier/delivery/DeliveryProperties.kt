package com.monimo.notifier.delivery

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("monimo.notifier.delivery")
data class DeliveryProperties(
    val enabled: Boolean = true,            // false 면 스케줄러를 켜지 않는다 (테스트는 pollOnce 를 직접 부른다)
    val pollInterval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
    val lease: Duration = Duration.ofSeconds(90),
    val retryBase: Duration = Duration.ofSeconds(5),
    val retryCap: Duration = Duration.ofMinutes(5),
    val maxAttempts: Int = 5,
    val maxAge: Duration = Duration.ofMinutes(30),
    val circuitFailureThreshold: Int = 5,    // 채널별 연속 실패가 이만큼이면 호출을 멈춘다 (E8)
    val circuitOpenDuration: Duration = Duration.ofSeconds(30),  // 멈춘 뒤 시험 호출까지 기다리는 시간
)

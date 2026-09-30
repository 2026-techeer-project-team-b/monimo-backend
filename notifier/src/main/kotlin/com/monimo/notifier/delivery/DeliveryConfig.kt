package com.monimo.notifier.delivery

import com.monimo.notifier.channel.DeliveryHttpProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Configuration
@EnableConfigurationProperties(DeliveryProperties::class)
class DeliveryConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun retryPolicy(props: DeliveryProperties, http: DeliveryHttpProperties): RetryPolicy {
        // 한 바퀴는 순서대로 보낸다. 임대가 "배치 × 호출 시간 한도"보다 짧으면 뒤쪽 작업은 호출 전에 임대가 끝나
        // 다른 워커가 같은 작업을 또 가져간다 → 기동할 때 막는다
        val worstBatch = http.requestTimeout.plus(http.connectTimeout).multipliedBy(props.batchSize.toLong())
        require(props.lease > worstBatch) { "lease(${props.lease})는 batch × (connect + request timeout) = $worstBatch 보다 길어야 합니다" }
        return RetryPolicy(props.retryBase, props.retryCap, props.maxAttempts, props.maxAge)
    }

    @Bean
    fun channelCircuitBreaker(props: DeliveryProperties, clock: Clock) =
        ChannelCircuitBreaker(props.circuitFailureThreshold, props.circuitOpenDuration, clock)
}

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "monimo.notifier.delivery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeliveryScheduler(private val worker: DeliveryWorker) {
    // fixedDelay: 앞 바퀴가 끝난 뒤 간격을 둔다 → 한 인스턴스 안에서 바퀴가 겹치지 않는다
    @Scheduled(fixedDelayString = "\${monimo.notifier.delivery.poll-interval:1s}")
    fun tick() {
        while (worker.pollOnce() > 0) Unit  // 쌓여 있으면 바로 다음 배치
    }
}

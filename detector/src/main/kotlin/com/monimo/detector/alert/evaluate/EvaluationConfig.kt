package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableConfigurationProperties(EvaluationProperties::class)
class EvaluationConfig {

    // 내부 문 공유 비밀값. API 서버와 같은 이름 · 같은 값 (MONIMO_INTERNAL_TOKEN)
    @Bean
    fun serviceHealthClient(
        props: EvaluationProperties,
        @Value("\${monimo.internal-token:}") internalToken: String,
        objectMapper: ObjectMapper,
    ): ServiceHealthClient = HttpServiceHealthClient(props.query, internalToken, objectMapper)

    // agents/active 도 같은 API 서버 · 같은 내부 토큰
    @Bean
    fun agentActivityClient(
        props: EvaluationProperties,
        @Value("\${monimo.internal-token:}") internalToken: String,
        objectMapper: ObjectMapper,
    ): AgentActivityClient = HttpAgentActivityClient(props.query, internalToken, objectMapper)
}

// 스케줄러는 따로 켜고 끈다. 테스트는 enabled=false 로 두고 runOnce 를 직접 부른다
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "monimo.alert.schedule", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EvaluationSchedulingConfig {
    @Bean
    fun evaluationScheduler(runner: EvaluationRunner, agentDown: AgentDownRunner) = EvaluationScheduler(runner, agentDown)
}

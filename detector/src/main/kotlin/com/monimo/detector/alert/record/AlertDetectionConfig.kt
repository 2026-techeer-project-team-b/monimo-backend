package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.AlertPolicy
import com.monimo.detector.alert.state.AlertStateMachine
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

// FN-28 N · M 은 규칙별 컬럼이 없어 전역 설정으로 시작한다 (2026-09-27 결정, docs/alert/10-state-machine.md §4)
@ConfigurationProperties("monimo.alert.evaluation")
data class AlertEvaluationProperties(
    val fireAfter: Int = 3,
    val resolveAfter: Int = 2,
    val maxBucketGap: Duration? = null,
)

@Configuration
@EnableConfigurationProperties(AlertEvaluationProperties::class)
class AlertDetectionConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun alertStateMachine(props: AlertEvaluationProperties) =
        AlertStateMachine(AlertPolicy(props.fireAfter, props.resolveAfter, props.maxBucketGap))

    @Bean
    fun transitionHook(): TransitionHook = TransitionHook.NONE
}

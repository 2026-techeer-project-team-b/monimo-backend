package com.monimo.ingester.job.dlq

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant

// 되넣는 잡을 **프로필 하나로** 켠다 (#195).
//
// 같은 이미지로 돌린다 : docker compose run --rm dlq-reprocess
//   → SPRING_PROFILES_ACTIVE=local,dlq-reprocess
// application-dlq-reprocess.yml 이 둘을 끈다 : 웹 서버(안 받는다) · 리스너 자동 시작(raw 를 읽지 않는다).
// 쿠버네티스가 생기면 **같은 이미지 · 같은 환경변수**를 Job 의 command 로 옮기면 끝이다
// (#119 가 clickhouse-migrate 에 대해 같은 길을 걸었다).
//
// 평소 적재 처리기(local 프로필만)에는 이 빈이 **아예 생기지 않는다.** 코드가 섞이는 것을 프로필로 막는다
@Configuration(proxyBeanMethods = false)
@Profile(PROFILE)
@EnableConfigurationProperties(DlqReprocessProperties::class)
class DlqReprocessConfig {

    @Bean
    fun dlqReprocessJob(
        consumerFactory: ConsumerFactory<*, *>,
        template: KafkaTemplate<*, *>,
        props: DlqReprocessProperties,
    ) = DlqReprocessJob(consumerFactory, template, props)

    // 떠서 한 번 돌고 끝난다. 웹 서버가 없고 리스너도 안 떠서 이 runner 가 끝나면 JVM 이 스스로 내려간다.
    // 멈춤(사람을 불러야 하는 상태)이면 예외를 던져 종료코드가 0 이 아니게 한다 :
    // docker compose run 의 종료코드로 바로 보이고, 나중에 쿠버네티스 Job 의 실패 판정이 그대로 걸린다
    @Bean
    fun dlqReprocessRunner(job: DlqReprocessJob, props: DlqReprocessProperties) = ApplicationRunner {
        log.info(
            "되넣기 시작 : {} → {} (그룹 {}, 포기 {}회{})",
            props.sourceTopic, props.targetTopic, props.groupId, props.maxAttempt,
            if (props.dryRun) ", 마른 실행" else "",
        )
        val result = job.run()
        log.info(
            "되넣기 끝 : 읽음 {} · 되넣음 {} · 건너뜀 {}{}",
            result.read, result.replayed, result.skipped,
            if (result.stopped) " · 멈춤" else "",
        )
        // 되넣기는 원본만 되살린다. 집계 구멍은 사람이 대조해 다시 만들어야 하므로 그 범위를 찍어 준다
        if (!result.window.empty) {
            log.info(
                "되넣은 구간 : {} ~ {} (UTC, 원래 발행 시각 기준. 데이터 안의 시각은 조금 더 앞일 수 있어 분을 넉넉히 잡는다). " +
                    "이 구간을 scripts/check-pipeline.sh 로 대조하고 어긋나면 " +
                    "docs/kafka-reprocess/experiments/reaggregate_window.sh 로 다시 만든다",
                Instant.ofEpochMilli(result.window.fromMs), Instant.ofEpochMilli(result.window.toMs),
            )
        }
        if (result.stopped) {
            error("되넣기를 멈췄다 : ${props.maxAttempt}번 넘게 왕복한 레코드가 있다. 위 로그의 오프셋을 보고 사람이 정한다")
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DlqReprocessConfig::class.java)
    }
}

// 이 글자가 프로필 이름이고 compose 서비스 이름이기도 하다
const val PROFILE = "dlq-reprocess"

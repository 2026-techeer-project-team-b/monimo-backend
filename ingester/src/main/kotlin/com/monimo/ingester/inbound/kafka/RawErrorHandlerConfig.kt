package com.monimo.ingester.inbound.kafka

import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeaders
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.ContainerPausingBackOffHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerContainerPauseService
import org.springframework.kafka.listener.ListenerContainerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration

// RawConsumer.onMessage 가 예외를 던졌을 때 무엇을 할지 정하는 곳 (ADR #51).
//
// 지금까지는 이 설정이 없어서 스프링 기본값(SeekUtils.DEFAULT_BACK_OFF = FixedBackOff(0, 9))이 쓰였다 :
//   간격 0초로 10번(약 4초) 시도하고 → 기본 복구 담당(로그 한 줄)을 부르고 → 오프셋을 넘겼다.
//   ClickHouse 가 10초만 죽어 있어도 그 사이 메시지가 영구 유실됐다. 직접 재현했다 (spans 226,417줄 그대로, 대조군 +4).
//
// 바꾸는 것 셋 :
//   ① 기다리는 방법 = pause. 스레드를 재우지 않고 파티션에 "지금은 안 받는다" 표시만 한다. poll() 은 계속 돌아
//      Kafka 가 "살아 있다" 고 보므로 max.poll.interval.ms(5분) 천장이 걸리지 않는다. 그래서 10분을 기다릴 수 있다
//   ② 복구 담당 = raw.dlq 로 보내기. 로그만 찍고 넘기는 기본값 대신 메시지를 옮겨 담는다
//   ③ 실패 종류마다 다른 대기 시간. 독성은 0 · 확실한 일시 장애는 10분 · 모르는 것은 1분 (FailureClassifier · RetryProperties)
//
// 이 빈을 만들어 두면 Spring Boot 가 리스너 컨테이너 팩토리에 자동으로 꽂는다. RawConsumer 는 손대지 않는다 :
// 에러 핸들러는 리스너 밖에서 동작하므로 onMessage 안에 try-catch 를 넣으면 오히려 안 불린다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetryProperties::class)
class RawErrorHandlerConfig {

    // pause 를 풀어 주는 타이머. Boot 는 @EnableScheduling 이 없으면 TaskScheduler 를 안 만들어 줘서 직접 둔다.
    // pause/resume 예약만 하므로 스레드 하나로 충분하다
    @Bean
    fun pauseScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("raw-pause-")
        initialize()
    }

    // "이 컨테이너를 이만큼 멈춰라" 를 받아 그 시간 뒤 스스로 resume 하는 부품. ContainerPausingBackOffHandler 가 쓴다
    @Bean
    fun listenerContainerPauseService(registry: ListenerContainerRegistry, pauseScheduler: ThreadPoolTaskScheduler) =
        ListenerContainerPauseService(registry, pauseScheduler)

    @Bean
    fun rawErrorHandler(
        template: KafkaTemplate<*, *>, // Boot 가 만든 것. application.yml 의 producer 직렬화 설정(String 키 · ByteArray 값)을 쓴다
        properties: RetryProperties,
        counter: DlqCounter,
        pauseService: ListenerContainerPauseService,
    ): DefaultErrorHandler {
        // 복구 담당 : raw.dlq 로 보낸다. 기본 대상은 "<토픽>-dlt" 라 리졸버를 직접 준다.
        // 파티션은 원본 번호를 그대로 넘긴다. raw 는 3개 · raw.dlq 는 1개라 어긋나는데, verifyPartition(기본 true)이
        // 목적지에 그 번호가 없으면 비워서 Kafka 가 고르게 한다. 그래서 verifyPartition 은 건드리지 않는다 (ADR #51 채택 ⑦)
        val dlq = DeadLetterPublishingRecoverer(template) { record, _ -> TopicPartition(properties.dlqTopic, record.partition()) }

        // DLQ 발행이 실패하면 예외를 던져 오프셋이 넘어가지 않게 한다. 안 켜면 "보냈다" 고 치고 넘어가 조용히 유실된다.
        // 발행 실패 = Kafka 자체 문제라 그때 막히는 것은 맞는 동작이다 (ADR #51 채택 ⑤)
        dlq.setFailIfSendResultIsError(true)

        // DLQ 레코드에 우리 헤더 둘을 더한다.
        //   x-dlq-attempt : 이 레코드가 DLQ 를 몇 번 거쳤나. 재처리 잡(범위 밖)이 "N번 넘으면 포기" 를 판단할 때 쓴다.
        //                   자동 헤더(kafka_dlt-exception-*)는 stripPreviousExceptionHeaders 기본값 때문에 매번 덮어써져 카운터로 못 쓴다
        //   x-dlq-reason  : 어느 분류로 왔나 (poison · transient · unknown). 재처리 잡이 독성은 건너뛰게
        dlq.setHeadersFunction { record, failure ->
            val attempt = record.headers().lastHeader(HEADER_ATTEMPT)?.value()?.let { String(it).toIntOrNull() } ?: 0
            RecordHeaders().apply {
                add(HEADER_ATTEMPT, (attempt + 1).toString().toByteArray())
                add(HEADER_REASON, FailureClassifier.classify(failure).name.lowercase().toByteArray())
            }
        }

        // 보낸 뒤에 세고 로그를 남긴다. 보내기가 실패하면(위 setFailIfSendResultIsError) 세지 않는다.
        //
        // 꼭 ConsumerAware 판이어야 한다. verifyPartition 은 consumer 로 목적지 파티션 수를 물어보는데, 두 인자 판
        // accept(record, failure) 로 감싸면 consumer 가 null 로 넘어가 그 검사가 조용히 꺼진다. 그러면 raw 파티션 1 · 2 에서
        // 실패한 것이 raw.dlq 파티션 1 · 2(없음)로 가려다 60초 메타데이터 대기 뒤 실패하고, failIfSendResultIsError 때문에
        // 그 레코드에서 영원히 멈춘다. 처음 구현에서 그렇게 됐고 수동 검증(키 logs 로 파티션 1)에서 잡았다
        val recoverer = ConsumerAwareRecordRecoverer { record, consumer, failure ->
            val reason = FailureClassifier.classify(failure)
            dlq.accept(record, consumer, failure)
            counter.sent(reason)
            log.warn(
                "raw-{}@{} → {} (reason={}, cause={})",
                record.partition(), record.offset(), properties.dlqTopic, reason.name.lowercase(), rootMessage(failure),
            )
        }

        // 세 번째 인자가 pause 방식. 안 주면 기본 핸들러가 스레드를 재워 5분 천장에 걸린다
        return DefaultErrorHandler(recoverer, exponential(properties, properties.unknownMaxElapsed), ContainerPausingBackOffHandler(pauseService)).apply {
            // 예외마다 다른 대기 규칙. 분류가 여기서 "얼마나 기다리나" 로 바뀐다.
            // 데이터 오류와 서버 과부하가 같은 타입(ServerException)이고 코드만 달라서, 타입으로 가르는 addNotRetryableExceptions 는 못 쓴다
            setBackOffFunction { _, failure ->
                when (FailureClassifier.classify(failure)) {
                    FailureClass.POISON -> NO_RETRY // 재시도 0회 = 바로 복구 담당(DLQ)
                    FailureClass.TRANSIENT -> exponential(properties, properties.transientMaxElapsed) // 10분
                    FailureClass.UNKNOWN -> exponential(properties, properties.unknownMaxElapsed) // 1분
                }
            }
        }
    }

    // 2초 → 4초 → 8초 → 16초 → 30초 → 30초 ... 총 maxElapsed 까지. FailedRecordTracker 가 레코드(오프셋)마다 따로 세므로
    // ClickHouse 가 1시간 죽어 있어도 DLQ 로 가는 것은 10분에 한 건씩이다 (홍수가 아니다)
    private fun exponential(p: RetryProperties, maxElapsed: Duration): BackOff =
        ExponentialBackOff(p.initialInterval.toMillis(), p.multiplier).apply {
            maxInterval = p.maxInterval.toMillis()
            maxElapsedTime = maxElapsed.toMillis()
        }

    // 로그용. 원인 사슬의 맨 안쪽 메시지 (예: "clickhouse" 라는 UnknownHostException 메시지)
    private fun rootMessage(t: Throwable): String? {
        var cur = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur.message ?: cur::class.simpleName
    }

    companion object {
        const val HEADER_ATTEMPT = "x-dlq-attempt"
        const val HEADER_REASON = "x-dlq-reason"
        private val NO_RETRY: BackOff = FixedBackOff(0L, 0L) // maxAttempts 0 = 첫 실패에 바로 STOP
        private val log = LoggerFactory.getLogger(RawErrorHandlerConfig::class.java)
    }
}

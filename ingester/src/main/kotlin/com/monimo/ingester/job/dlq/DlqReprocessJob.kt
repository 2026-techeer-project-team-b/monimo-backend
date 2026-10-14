package com.monimo.ingester.job.dlq

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.util.Properties

// raw.dlq 를 앞에서부터 읽어 raw 로 다시 보낸다 (#195, ADR 참조).
//
// 왜 raw 로 되넣나 : 적재 처리기에 새 코드가 필요 없다. 키가 그대로라 같은 파티션에 들어가고
// 지금 돌고 있는 ingester 그룹이 평소처럼 집어 간다. ADR #34 가 정한 모양이기도 하다.
//
// **되넣기는 원본에 없는 것만 되살린다.** 원본에 줄이 있고 집계에만 구멍이 있는 경우
// (while pushing to view 처럼 MV 가 터진 경우)는 이 잡으로 안 고쳐진다 : 중복 제거 기억이
// 블록을 버리고 deduplicate_blocks_in_dependent_materialized_views = 1 때문에 MV 가 아예 안 돈다.
// 실측했다(집계 줄을 지우고 되넣었더니 transactions 가 0 에서 안 올라왔다).
// 그 구멍은 원본에서 다시 집계해야 하고 그것은 손 절차다 :
//   scripts/check-pipeline.sh 가 원본 ↔ 집계를 대조하고
//   docs/kafka-reprocess/experiments/reaggregate_window.sh 가 그 1분 구간을 다시 만든다.
// 그래서 이 잡은 끝날 때 **되넣은 구간**을 찍어 준다. 사람이 그 구간을 대조할 수 있게.
//
// 빈으로 만드는 곳은 DlqReprocessConfig 다 : dlq-reprocess 프로필에서만 생긴다. 평소 적재 처리기에는 없다.
class DlqReprocessJob(
    consumerFactory: ConsumerFactory<*, *>,
    template: KafkaTemplate<*, *>,
    private val props: DlqReprocessProperties,
) {
    // 둘 다 와일드카드로 받아 캐스팅한다. 스프링이 만든 빈이 하나뿐이고 직렬화기는
    // application.yml 이 String 키 · ByteArray 값으로 못박아 둬서 실제 타입이 이것이다.
    // RawErrorHandlerConfig 가 KafkaTemplate<*, *> 를 받는 것과 같은 이유다
    @Suppress("UNCHECKED_CAST")
    private val consumerFactory = consumerFactory as ConsumerFactory<String, ByteArray>

    @Suppress("UNCHECKED_CAST")
    private val producer = template as KafkaTemplate<String, ByteArray>

    fun run(): Result {
        var r = Result()
        consumer().use { consumer ->
            consumer.subscribe(listOf(props.sourceTopic))

            // **이번 회차는 시작 시점의 끝까지만 읽는다.** 되넣은 것이 또 실패하면 raw.dlq 의 끝에 새 오프셋으로
            // 붙는데, 그 자리까지 읽으면 한 회차 안에서 같은 레코드를 여러 바퀴 돌린다.
            // 처음에는 "앞에서부터 읽으니 한 회차에서는 안 돈다" 고 생각했는데 **돌려 보니 돌았다** :
            // 빈 poll 이 날 때까지 읽는 고리가 방금 자기가 만든 실패분을 집어 갔다(attempt 3 → 4 를 한 회차에서 봤다).
            // 경계를 박아 두면 한 회차 = 한 바퀴가 되고, 이번에 만든 실패분은 다음 회차 몫이 된다
            var limits: Map<TopicPartition, Long>? = null
            var emptyPolls = 0
            while (emptyPolls < props.emptyPolls && r.read < props.maxRecords && !r.stopped && !r.roundDone) {
                val batch = consumer.poll(props.pollTimeout)
                // 할당은 첫 poll 에서 생긴다. 그때의 끝을 한 번만 적어 둔다 (아직 아무것도 안 보냈으므로 순수한 시작 시점이다)
                if (limits == null && consumer.assignment().isNotEmpty()) limits = consumer.endOffsets(consumer.assignment())
                if (batch.isEmpty) {
                    emptyPolls++
                    continue
                }
                emptyPolls = 0
                for (record in batch) {
                    val limit = limits?.get(TopicPartition(record.topic(), record.partition())) ?: Long.MAX_VALUE
                    if (record.offset() >= limit) {
                        // 이번 회차가 만든 실패분이다. 커밋하지 않고 끝낸다 = 다음 회차가 읽는다
                        log.info("{}@{} 부터는 이번 회차가 만든 것이라 다음 회차로 넘긴다", record.topic(), record.offset())
                        r = r.copy(roundDone = true)
                        break
                    }
                    r = handle(record, consumer, r)
                    if (r.stopped || r.read >= props.maxRecords) break
                }
            }
        }
        return r
    }

    private fun handle(record: ConsumerRecord<String, ByteArray>, consumer: Consumer<*, *>, acc: Result): Result {
        val attempt = DlqReprocessPlan.attemptOf(record.headers())
        val reason = DlqReprocessPlan.reasonOf(record.headers())
        val read = acc.read + 1

        return when (DlqReprocessPlan.decide(reason, attempt, props.maxAttempt)) {
            DlqReprocessPlan.Decision.STOP_EXHAUSTED -> {
                // 커밋하지 않고 끝낸다. 그래서 이 그룹의 lag 이 0 으로 안 내려가고, 점검 스크립트가 계속 말한다.
                // 뒤에 있는 멀쩡한 것까지 막히는 것은 맞다 : 사람이 부르는 잡이라 막히는 쪽이 보인다.
                // 같은 판단을 #96 이 DLQ 발행 실패에 대해 이미 했다(setFailIfSendResultIsError)
                log.error(
                    "{}@{} 를 {}번째로 되넣게 돼 멈춘다 (reason={}). 이 레코드를 사람이 보고 결정한다",
                    props.sourceTopic, record.offset(), attempt + 1, reason,
                )
                acc.copy(read = read, stopped = true)
            }

            DlqReprocessPlan.Decision.SKIP_POISON -> {
                // 되넣으면 그대로 또 실패한다. 데이터가 틀린 것이라 복구할 길이 없다
                log.warn("{}@{} 건너뛴다 : 데이터가 틀린 것이다 (attempt={})", props.sourceTopic, record.offset(), attempt)
                commit(consumer, record)
                acc.copy(read = read, skipped = acc.skipped + 1)
            }

            DlqReprocessPlan.Decision.REPLAY -> {
                val kept = DlqReprocessPlan.keep(record.headers())
                if (props.dryRun) {
                    log.info(
                        "[마른 실행] {}@{} → {} (key={}, {}바이트, 머리말 {}장, attempt={})",
                        props.sourceTopic, record.offset(), props.targetTopic,
                        record.key(), record.value().size, kept.size, attempt,
                    )
                } else {
                    // get() 으로 기다린다 : 보냈는지 확인하지 않고 커밋하면 조용히 유실된다.
                    // 실패하면 예외가 올라가 이 회차가 끝나고 커밋이 안 되므로 다음에 다시 읽는다
                    producer.send(
                        ProducerRecord(props.targetTopic, null, record.key(), record.value(), kept),
                    ).get()
                    log.info(
                        "{}@{} → {} (key={}, attempt={} → {})",
                        props.sourceTopic, record.offset(), props.targetTopic, record.key(), attempt, attempt + 1,
                    )
                }
                commit(consumer, record)
                // 구간은 **원래 raw 에 발행된** 시각으로 잡는다. DLQ 에 적힌 시각은 최대 10분 뒤라 엉뚱한 분을 가리킨다
                val originalMs = DlqReprocessPlan.originalTimestamp(record.headers()) ?: record.timestamp()
                acc.copy(read = read, replayed = acc.replayed + 1, window = acc.window.widen(originalMs))
            }
        }
    }

    // 레코드마다 커밋한다. 중간에 죽으면 그 다음부터 이어 읽는다 (적재 쪽 ack-mode: RECORD 와 같은 생각)
    private fun commit(consumer: Consumer<*, *>, record: ConsumerRecord<*, *>) {
        if (props.dryRun) return // 마른 실행은 장부를 건드리지 않는다
        consumer.commitSync(
            mapOf(TopicPartition(record.topic(), record.partition()) to OffsetAndMetadata(record.offset() + 1)),
        )
    }

    private fun consumer(): Consumer<String, ByteArray> {
        // 스스로 커밋하지 않게 못박는다. application.yml 에 이 값이 없어서 Kafka 기본값(true)이 쓰이는데,
        // 그러면 뒤에서 멋대로 커밋해 "보내지 않은 것을 했다고" 적을 수 있다.
        // 리스너 컨테이너는 자기가 알아서 false 로 쓰지만 이 잡은 컨슈머를 직접 만들므로 여기서 준다
        val overrides = Properties().apply {
            setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") // 장부가 없는 첫 회차는 앞에서부터
        }
        return consumerFactory.createConsumer(props.groupId, CLIENT_PREFIX, null, overrides)
    }

    // 한 회차의 결과. 되넣은 구간(window)은 사람이 원본 ↔ 집계를 대조할 범위다
    data class Result(
        val read: Int = 0,
        val replayed: Int = 0,
        val skipped: Int = 0,
        val stopped: Boolean = false,
        val roundDone: Boolean = false, // 시작 시점의 끝에 닿았다. 멈춤(stopped)과 달리 정상 종료다
        val window: Window = Window(),
    )

    data class Window(val fromMs: Long = -1, val toMs: Long = -1) {
        fun widen(ms: Long) = Window(if (fromMs < 0) ms else minOf(fromMs, ms), maxOf(toMs, ms))
        val empty: Boolean get() = fromMs < 0
    }

    private companion object {
        const val CLIENT_PREFIX = "dlq-reprocess"
        val log = LoggerFactory.getLogger(DlqReprocessJob::class.java)
    }
}

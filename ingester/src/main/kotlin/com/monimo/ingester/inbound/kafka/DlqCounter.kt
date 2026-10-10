package com.monimo.ingester.inbound.kafka

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

// raw.dlq 로 보낸 건수를 분류별로 센다. /actuator/metrics/monimo.ingester.dlq?tag=reason:poison 로 본다.
// 세 분류를 미리 등록해 한 건도 없을 때도 0 으로 보이게 한다 (check-pipeline.sh 가 읽는다).
// RawConsumeCounter 와 같은 꼴. 단위는 "메시지" 다 : 스팬 수가 아니라 Kafka 레코드 수
@Component
class DlqCounter(registry: MeterRegistry) {

    private val counters: Map<FailureClass, Counter> = FailureClass.entries.associateWith { reason ->
        Counter.builder(METRIC)
            .description("적재에 실패해 raw.dlq 로 보낸 메시지 수 (reason = poison · transient · unknown)")
            .baseUnit("messages")
            .tag("reason", reason.name.lowercase())
            .register(registry)
    }

    fun sent(reason: FailureClass) = counters.getValue(reason).increment()

    fun count(reason: FailureClass): Double = counters.getValue(reason).count()

    fun total(): Double = counters.values.sumOf { it.count() }

    companion object {
        const val METRIC = "monimo.ingester.dlq"
    }
}

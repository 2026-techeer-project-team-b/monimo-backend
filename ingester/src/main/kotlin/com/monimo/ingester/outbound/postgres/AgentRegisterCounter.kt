package com.monimo.ingester.outbound.postgres

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

// 파드 등록이 어떻게 끝났는지 센다. /actuator/metrics/monimo.ingester.agents 로 본다.
// 세 결과를 미리 등록해 한 번도 없었을 때도 0 으로 보이게 한다 (RawConsumeCounter 와 같은 방식)
@Component
class AgentRegisterCounter(registry: MeterRegistry) {

    private val counters: Map<Outcome, Counter> = Outcome.entries.associateWith { outcome ->
        Counter.builder(METRIC)
            .description("적재 처리기가 처음 본 파드를 agents 표에 등록한 결과")
            .baseUnit("agents")
            .tag("outcome", outcome.tag)
            .register(registry)
    }

    fun count(outcome: Outcome): Double = counters.getValue(outcome).count()

    fun increment(outcome: Outcome) = counters.getValue(outcome).increment()

    enum class Outcome(val tag: String) {
        REGISTERED("registered"), // 새 줄이 생겼다
        UNKNOWN_SERVICE("unknown_service"), // 화면에 등록되지 않았거나 제외된 서비스의 파드 — 넣을 수 없다
        FAILED("failed"), // PG 가 죽었거나 쿼리가 실패했다. 적재는 이미 끝났으므로 다음 메시지에서 다시 시도된다
    }

    companion object {
        const val METRIC = "monimo.ingester.agents"
    }
}

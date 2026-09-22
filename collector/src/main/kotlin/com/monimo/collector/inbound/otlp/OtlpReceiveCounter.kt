package com.monimo.collector.inbound.otlp

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

// 받은 건수를 신호별로 센다. /actuator/metrics/monimo.collector.otlp.received?tag=signal:traces 로 본다.
// 세 신호를 미리 등록해 두어 아직 한 건도 안 왔을 때도 0 으로 보이게 한다 (연결 점검 스크립트가 before/after 를 비교한다).
@Component
class OtlpReceiveCounter(registry: MeterRegistry) {

    private val counters: Map<Signal, Counter> = Signal.entries.associateWith { signal ->
        Counter.builder(METRIC)
            .description("수집기가 OTLP gRPC 로 받은 건수 (스팬 · 메트릭 · 로그 레코드)")
            .baseUnit("items")
            .tag("signal", signal.tag)
            .register(registry)
    }

    fun received(signal: Signal, count: Int) {
        if (count > 0) counters.getValue(signal).increment(count.toDouble())
    }

    fun count(signal: Signal): Double = counters.getValue(signal).count()

    enum class Signal(val tag: String) { TRACES("traces"), METRICS("metrics"), LOGS("logs") }

    companion object {
        const val METRIC = "monimo.collector.otlp.received"
    }
}

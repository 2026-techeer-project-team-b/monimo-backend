package com.monimo.notifier.delivery

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

// "주기 작업이 마지막으로 성공한 뒤 몇 초" 게이지 (ADR #54, 수집기 monimo.collector.sampling.refresh.age 와 같은 꼴).
// 성공 · 실패 카운터는 작업이 끝까지 돌아야 올라가서, JDBC 응답을 기다리며 스레드가 막히면 "정상이라 조용한 것"과 구분되지 않는다.
// 이 값은 읽는 순간 계산하므로 작업이 막혀 있어도 계속 커진다. 임계값 · 경보 규칙은 여기서 정하지 않는다.
// 한 번도 성공하지 못했으면 기동 시각부터 센다 (0 이나 아주 큰 값으로 두면 뜻이 틀린 숫자가 된다)
class LastSuccessAge(registry: MeterRegistry, name: String, description: String) {
    private val clock = registry.config().clock()
    private val lastSuccessNanos = AtomicLong(clock.monotonicTime())

    init {
        Gauge.builder(name) { seconds() }
            .description(description)
            .baseUnit("seconds")
            .register(registry)
    }

    fun markSuccess() = lastSuccessNanos.set(clock.monotonicTime())

    fun seconds(): Double = (clock.monotonicTime() - lastSuccessNanos.get()) / NANOS_PER_SECOND

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

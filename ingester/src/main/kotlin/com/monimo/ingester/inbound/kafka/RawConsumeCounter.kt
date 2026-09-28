package com.monimo.ingester.inbound.kafka // 들어오는 문(inbound) — Kafka 쪽

import com.monimo.common.kafka.RawSignal // 신호 3종 약속 (수집기와 같은 파일을 본다)
import io.micrometer.core.instrument.Counter // 숫자를 세는 계량기 (한 번 오르면 안 내려간다)
import io.micrometer.core.instrument.MeterRegistry // 계량기를 등록해 두는 곳. 스프링이 넣어 준다
import org.springframework.stereotype.Component // "스프링이 이 객체를 만들어 관리한다" 표시

// 푼 건수를 신호별로 센다. /actuator/metrics/monimo.ingester.raw.consumed?tag=signal:traces 로 본다.
// 세 신호를 미리 등록해 두어 한 건도 안 왔을 때도 0 으로 보이게 한다 (점검 스크립트가 before/after 를 비교한다).
// 수집기의 OtlpReceiveCounter 와 같은 모양이지만, 신호 enum 은 common 의 RawSignal 을 그대로 쓴다.
@Component
class RawConsumeCounter(registry: MeterRegistry) {

    // 신호 3개 각각에 계량기를 하나씩 만들어 map 으로 들고 있는다.
    // associateWith = 목록의 각 항목을 키로, 중괄호가 만든 값을 값으로 하는 map 을 만든다 (TRACES -> 계량기, ...)
    private val counters: Map<RawSignal, Counter> = RawSignal.entries.associateWith { signal ->
        Counter.builder(METRIC) // 이름은 셋 다 같고
            .description("적재 처리기가 raw 토픽에서 꺼내 푼 건수 (스팬 · 메트릭 · 로그 레코드)")
            .baseUnit("items")
            .tag("signal", signal.key) // 태그(signal=traces ...)로 구분한다
            .register(registry) // 등록해야 /actuator/metrics 에 보인다
    }

    // 센다. 0건짜리 메시지는 굳이 건드리지 않는다
    fun consumed(signal: RawSignal, count: Int) {
        if (count > 0) counters.getValue(signal).increment(count.toDouble())
    }

    // 지금까지 센 값. 테스트와 점검 스크립트가 확인할 때 쓴다
    fun count(signal: RawSignal): Double = counters.getValue(signal).count()

    companion object { // 객체마다가 아니라 클래스에 하나만 두는 상수
        const val METRIC = "monimo.ingester.raw.consumed"
    }
}

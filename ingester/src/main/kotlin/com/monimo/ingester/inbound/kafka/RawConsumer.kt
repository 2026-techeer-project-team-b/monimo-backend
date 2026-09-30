package com.monimo.ingester.inbound.kafka

import com.monimo.common.kafka.RawSignal // 토픽 이름 · 키 약속 (수집기가 넣을 때 쓴 것과 같은 파일)
import com.monimo.ingester.transform.SpanStore // 스팬을 저장하는 곳 (무엇으로 저장하는지는 모른다)
import com.monimo.ingester.transform.SpanTranslator // OTLP → 우리 모델 변환
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import org.apache.kafka.clients.consumer.ConsumerRecord // Kafka 에서 꺼낸 메시지 한 개 (키 · 값 · 오프셋)
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener // "이 함수가 토픽을 구독한다" 표시
import org.springframework.stereotype.Component

// raw 토픽에서 꺼내 protobuf 로 푸는 곳.
// 트레이스는 우리 모델로 옮겨 ClickHouse 에 넣는다. 메트릭 · 로그 적재는 다음 이슈.
//
// 수집기와 정확히 대칭이다:
//   수집기      request.toByteArray()  → Kafka        (넣을 때 객체를 바이트로)
//   적재 처리기  Kafka → parseFrom(record.value())     (꺼낼 때 바이트를 다시 객체로)
@Component
class RawConsumer(
    private val counter: RawConsumeCounter, // 카운터는 스프링이 넣어 준다
    private val spanStore: SpanStore, // 포트. 실제로 들어오는 것은 ClickHouseSpanStore 지만 이 클래스는 그걸 모른다
) {

    // 스프링이 알아서 소비자를 만들어 메시지를 하나씩 이 함수에 넣어 준다.
    // 그룹 이름(ingester) · 바이트로 읽기 · 처음부터 읽기는 application.yml 의 spring.kafka.consumer 가 정한다
    @KafkaListener(topics = [RawSignal.TOPIC])
    fun onMessage(record: ConsumerRecord<String, ByteArray>) {
        val signal = RawSignal.fromKey(record.key()) // 키("traces") → 항목(TRACES). 모르는 키면 예외가 난다

        // 신호별로 알맞은 protobuf 로 푼다. enum 이라 세 경우를 다 다뤘는지 컴파일러가 검사해 준다.
        // 데이터가 resource → scope → 신호 3겹 구조라 sumOf 를 두 번 겹쳐 안쪽까지 더한다 (수집기가 셀 때와 같은 방식)
        val count = when (signal) {
            // 트레이스만 적재한다. 풀어서 우리 모델로 옮긴 뒤 한 번에 넣는다
            RawSignal.TRACES -> {
                val rows = SpanTranslator.toRows(ExportTraceServiceRequest.parseFrom(record.value()))
                spanStore.save(rows)
                rows.size
            }

            RawSignal.METRICS -> ExportMetricsServiceRequest.parseFrom(record.value())
                .resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { it.metricsCount } }

            RawSignal.LOGS -> ExportLogsServiceRequest.parseFrom(record.value())
                .resourceLogsList.sumOf { rl -> rl.scopeLogsList.sumOf { it.logRecordsCount } }
        }

        counter.consumed(signal, count) // 센다
        // 오프셋 = 토픽 안에서 이 메시지의 자리 번호. 어디까지 읽었는지 추적할 때 쓴다
        log.debug("raw 소비: {} {}건 (오프셋 {})", signal.key, count, record.offset())
    }

    private companion object {
        val log = LoggerFactory.getLogger(RawConsumer::class.java) // 클래스가 있으니 클래스를 넘긴다 (로거 이름 자동)
    }
}

package com.monimo.ingester.inbound.kafka

import com.monimo.common.kafka.RawSignal // 토픽 이름 · 키 약속 (수집기가 넣을 때 쓴 것과 같은 파일)
import com.monimo.ingester.transform.AgentRegistry // 처음 본 파드를 명단에 올리는 곳
import com.monimo.ingester.transform.AgentSighting
import com.monimo.ingester.transform.LogStore
import com.monimo.ingester.transform.LogTranslator
import com.monimo.ingester.transform.MetricStore
import com.monimo.ingester.transform.MetricTranslator
import com.monimo.ingester.transform.SpanStore // 저장하는 곳 (무엇으로 저장하는지는 모른다)
import com.monimo.ingester.transform.SpanTranslator // OTLP → 우리 모델 변환
import com.monimo.ingester.transform.sighting // resource → "이 파드를 봤다" 기록
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import org.apache.kafka.clients.consumer.ConsumerRecord // Kafka 에서 꺼낸 메시지 한 개 (키 · 값 · 오프셋)
import org.slf4j.LoggerFactory
import io.opentelemetry.proto.resource.v1.Resource
import org.springframework.kafka.annotation.KafkaListener // "이 함수가 토픽을 구독한다" 표시
import org.springframework.stereotype.Component
import java.time.Instant

// raw 토픽에서 꺼내 protobuf 로 풀고, 우리 모델로 옮겨 ClickHouse 에 넣는 곳. 세 신호 모두.
// 넣은 뒤에는 그 신호를 보낸 파드를 PG agents 표에 등록한다 (처음 보는 파드만).
//
// 수집기와 정확히 대칭이다:
//   수집기      request.toByteArray()  → Kafka        (넣을 때 객체를 바이트로)
//   적재 처리기  Kafka → parseFrom(record.value())     (꺼낼 때 바이트를 다시 객체로)
@Component
class RawConsumer(
    private val counter: RawConsumeCounter, // 카운터는 스프링이 넣어 준다
    // 포트 셋. 실제로 들어오는 것은 ClickHouse*Store 지만 이 클래스는 그걸 모른다
    private val spanStore: SpanStore,
    private val metricStore: MetricStore,
    private val logStore: LogStore,
    private val agentRegistry: AgentRegistry, // PG 쪽 포트. 구현은 PostgresAgentRegistry 지만 이 클래스는 모른다
) {

    // 스프링이 알아서 소비자를 만들어 메시지를 하나씩 이 함수에 넣어 준다.
    // 그룹 이름(ingester) · 바이트로 읽기 · 처음부터 읽기는 application.yml 의 spring.kafka.consumer 가 정한다
    @KafkaListener(topics = [RawSignal.TOPIC])
    fun onMessage(record: ConsumerRecord<String, ByteArray>) {
        val signal = RawSignal.fromKey(record.key()) // 키("traces") → 항목(TRACES). 모르는 키면 예외가 난다

        // 신호별로 알맞은 protobuf 로 풀고 → 변환 → 저장. enum 이라 세 경우를 다 다뤘는지 컴파일러가 검사해 준다.
        //
        // 카운터는 수집기와 같은 단위로 센다 (스팬 수 · 메트릭 수 · 레코드 수). check-pipeline.sh 가 두 숫자를 대조하기 때문이다.
        // 메트릭은 "줄 수" 와 "메트릭 수" 가 다르다 — 히스토그램 하나가 .count · .sum · .min · .max 4줄이 된다. 줄 수는 로그에만 남긴다
        // 적재하면서 이 메시지에 실려 온 resource 목록을 같이 모아 둔다. 파드 등록 재료다
        val resources: List<Resource>
        val count = when (signal) {
            RawSignal.TRACES -> {
                val request = ExportTraceServiceRequest.parseFrom(record.value())
                val rows = SpanTranslator.toRows(request)
                spanStore.save(rows)
                resources = request.resourceSpansList.map { it.resource }
                rows.size // 스팬 1개 = 1줄이라 줄 수가 곧 스팬 수
            }

            RawSignal.METRICS -> {
                val request = ExportMetricsServiceRequest.parseFrom(record.value())
                val rows = MetricTranslator.toRows(request)
                metricStore.save(rows)
                resources = request.resourceMetricsList.map { it.resource }
                log.debug("metrics {}개 → metrics_raw {}줄", request.metricCount(), rows.size)
                request.metricCount()
            }

            RawSignal.LOGS -> {
                val request = ExportLogsServiceRequest.parseFrom(record.value())
                val rows = LogTranslator.toRows(request)
                logStore.save(rows)
                resources = request.resourceLogsList.map { it.resource }
                rows.size // 레코드 1개 = 1줄
            }
        }

        // 적재 뒤에 등록한다. PG 가 죽어도 ClickHouse 적재는 되어야 하기 때문이다.
        // 등록은 어댑터 안에서 실패를 잡아 로그와 카운터만 남기므로, 여기서 메시지 처리가 멈추지 않는다
        agentRegistry.register(sightingsOf(resources))

        counter.consumed(signal, count) // 센다
        // 오프셋 = 토픽 안에서 이 메시지의 자리 번호. 어디까지 읽었는지 추적할 때 쓴다
        log.debug("raw 소비: {} {}건 (오프셋 {})", signal.key, count, record.offset())
    }

    // resource 마다 "이 파드를 봤다" 기록 하나. 본 시각은 지금으로 둔다 (신호 안의 시각은 에이전트 시계라 믿지 않는다)
    private fun sightingsOf(resources: List<Resource>): List<AgentSighting> {
        val now = Instant.now()
        return resources.map { it.sighting(now) }
    }

    // 요청 안의 메트릭 개수. 수집기(OtlpExportServices)가 세는 것과 같은 식
    private fun ExportMetricsServiceRequest.metricCount(): Int =
        resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { it.metricsCount } }

    private companion object {
        val log = LoggerFactory.getLogger(RawConsumer::class.java) // 클래스가 있으니 클래스를 넘긴다 (로거 이름 자동)
    }
}

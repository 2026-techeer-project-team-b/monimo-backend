package com.monimo.collector.inbound.otlp

import com.monimo.collector.outbound.kafka.RawProducer
import com.monimo.common.kafka.RawSignal
import io.grpc.stub.StreamObserver
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// OTLP 들어오는 문. 받은 요청을 풀지 않고 protobuf 바이트 그대로 Kafka raw 에 넣고, 넣기가 끝나면 응답한다.
// 샘플링(trace ID 해시 + 카나리 예외)은 다음 이슈에서 받기와 발행 사이에 들어간다.
// 부분 실패(partial_success)는 쓰지 않는다. 받았으면 전부 받은 것이다.

@Component
class OtlpTraceService(
    private val counter: OtlpReceiveCounter,
    private val producer: RawProducer,
) : TraceServiceGrpc.TraceServiceImplBase() {

    override fun export(request: ExportTraceServiceRequest, responseObserver: StreamObserver<ExportTraceServiceResponse>) {
        val spans = request.resourceSpansList.sumOf { rs -> rs.scopeSpansList.sumOf { it.spansCount } }
        counter.received(OtlpReceiveCounter.Signal.TRACES, spans)
        log.debug("OTLP traces 수신: 스팬 {}건 (resource {}개)", spans, request.resourceSpansCount)
        responseObserver.respondAfter(
            sent = producer.send(RawSignal.TRACES, request.toByteArray()),
            response = ExportTraceServiceResponse.getDefaultInstance(),
            signal = "traces",
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpTraceService::class.java)
    }
}

@Component
class OtlpMetricsService(
    private val counter: OtlpReceiveCounter,
    private val producer: RawProducer,
) : MetricsServiceGrpc.MetricsServiceImplBase() {

    override fun export(request: ExportMetricsServiceRequest, responseObserver: StreamObserver<ExportMetricsServiceResponse>) {
        val metrics = request.resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { it.metricsCount } }
        counter.received(OtlpReceiveCounter.Signal.METRICS, metrics)
        log.debug("OTLP metrics 수신: 메트릭 {}건 (resource {}개)", metrics, request.resourceMetricsCount)
        responseObserver.respondAfter(
            sent = producer.send(RawSignal.METRICS, request.toByteArray()),
            response = ExportMetricsServiceResponse.getDefaultInstance(),
            signal = "metrics",
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpMetricsService::class.java)
    }
}

@Component
class OtlpLogsService(
    private val counter: OtlpReceiveCounter,
    private val producer: RawProducer,
) : LogsServiceGrpc.LogsServiceImplBase() {

    override fun export(request: ExportLogsServiceRequest, responseObserver: StreamObserver<ExportLogsServiceResponse>) {
        val records = request.resourceLogsList.sumOf { rl -> rl.scopeLogsList.sumOf { it.logRecordsCount } }
        counter.received(OtlpReceiveCounter.Signal.LOGS, records)
        log.debug("OTLP logs 수신: 로그 레코드 {}건 (resource {}개)", records, request.resourceLogsCount)
        responseObserver.respondAfter(
            sent = producer.send(RawSignal.LOGS, request.toByteArray()),
            response = ExportLogsServiceResponse.getDefaultInstance(),
            signal = "logs",
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpLogsService::class.java)
    }
}

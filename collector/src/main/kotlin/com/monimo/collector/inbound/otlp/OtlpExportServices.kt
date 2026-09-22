package com.monimo.collector.inbound.otlp

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

// OTLP 수신 골격 (개발환경 6단계 연결 약속). 지금은 받아서 세기만 하고 그대로 성공 응답을 돌려준다.
// 샘플링 · Kafka raw 발행은 다음 이슈에서 이 세 클래스 뒤에 붙는다.
// 부분 실패(partial_success)는 쓰지 않는다. 받았으면 전부 받은 것이다.

@Component
class OtlpTraceService(private val counter: OtlpReceiveCounter) : TraceServiceGrpc.TraceServiceImplBase() {

    override fun export(request: ExportTraceServiceRequest, responseObserver: StreamObserver<ExportTraceServiceResponse>) {
        val spans = request.resourceSpansList.sumOf { rs -> rs.scopeSpansList.sumOf { it.spansCount } }
        counter.received(OtlpReceiveCounter.Signal.TRACES, spans)
        log.debug("OTLP traces 수신: 스팬 {}건 (resource {}개)", spans, request.resourceSpansCount)
        responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance())
        responseObserver.onCompleted()
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpTraceService::class.java)
    }
}

@Component
class OtlpMetricsService(private val counter: OtlpReceiveCounter) : MetricsServiceGrpc.MetricsServiceImplBase() {

    override fun export(request: ExportMetricsServiceRequest, responseObserver: StreamObserver<ExportMetricsServiceResponse>) {
        val metrics = request.resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { it.metricsCount } }
        counter.received(OtlpReceiveCounter.Signal.METRICS, metrics)
        log.debug("OTLP metrics 수신: 메트릭 {}건 (resource {}개)", metrics, request.resourceMetricsCount)
        responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance())
        responseObserver.onCompleted()
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpMetricsService::class.java)
    }
}

@Component
class OtlpLogsService(private val counter: OtlpReceiveCounter) : LogsServiceGrpc.LogsServiceImplBase() {

    override fun export(request: ExportLogsServiceRequest, responseObserver: StreamObserver<ExportLogsServiceResponse>) {
        val records = request.resourceLogsList.sumOf { rl -> rl.scopeLogsList.sumOf { it.logRecordsCount } }
        counter.received(OtlpReceiveCounter.Signal.LOGS, records)
        log.debug("OTLP logs 수신: 로그 레코드 {}건 (resource {}개)", records, request.resourceLogsCount)
        responseObserver.onNext(ExportLogsServiceResponse.getDefaultInstance())
        responseObserver.onCompleted()
    }

    private companion object {
        val log = LoggerFactory.getLogger(OtlpLogsService::class.java)
    }
}

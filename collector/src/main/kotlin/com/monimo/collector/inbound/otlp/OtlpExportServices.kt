package com.monimo.collector.inbound.otlp

import com.monimo.collector.outbound.kafka.RawProducer // 나가는 문 (Kafka raw 발행)
import com.monimo.common.kafka.RawSignal // 토픽 이름 · 키 약속
import io.grpc.stub.StreamObserver // 에이전트에게 답장을 써 보내는 통로
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
//
// 세 클래스(트레이스 · 메트릭 · 로그)는 구조가 똑같다. 아래 트레이스에 자세히 적었고, 나머지는 다른 점만 표시한다.

@Component // 스프링이 객체를 하나 만들어 두고, gRPC 서버에 등록해 준다
class OtlpTraceService(
    private val counter: OtlpReceiveCounter, // 받은 건수를 세는 카운터 (스프링이 넣어 준다)
    private val producer: RawProducer, // 나가는 문 (스프링이 넣어 준다)
) : TraceServiceGrpc.TraceServiceImplBase() { // : 는 상속. OTel 이 정해 둔 TraceService 를 우리가 구현한다

    // override = 부모가 정해 둔 함수를 우리 내용으로 덮어쓴다. 에이전트가 트레이스를 보내면 gRPC 가 이걸 불러 준다
    // request = 받은 데이터 · responseObserver = 답장 통로
    override fun export(request: ExportTraceServiceRequest, responseObserver: StreamObserver<ExportTraceServiceResponse>) {
        // 스팬 개수 세기. 데이터가 resource → scope → span 3겹이라 안쪽까지 더한다 (sumOf = 합계)
        val spans = request.resourceSpansList.sumOf { rs -> rs.scopeSpansList.sumOf { it.spansCount } }
        counter.received(OtlpReceiveCounter.Signal.TRACES, spans) // 카운터 증가 (연결 점검 스크립트가 이 값을 본다)
        log.debug("OTLP traces 수신: 스팬 {}건 (resource {}개)", spans, request.resourceSpansCount) // {} 자리에 값이 들어간다
        responseObserver.respondAfter( // 응답은 규칙 함수에 맡긴다 (Kafka 저장이 끝난 뒤에 답장한다)
            sent = producer.send(RawSignal.TRACES, request.toByteArray()), // 핵심: 풀지 않고 바이트 그대로 raw 에 넣기 시작
            response = ExportTraceServiceResponse.getDefaultInstance(), // 성공 때 보낼 빈 응답
            signal = "traces", // 로그에 찍을 이름
        )
    }

    private companion object { // 객체마다 하나가 아니라 클래스에 하나만 두는 로거
        val log = LoggerFactory.getLogger(OtlpTraceService::class.java) // 클래스가 있으니 클래스를 넘긴다 (이름 자동)
    }
}

// 트레이스와 같은 구조. 다른 점 = 세는 대상(메트릭 개수) · 신호 이름(METRICS) · 응답 타입
@Component
class OtlpMetricsService(
    private val counter: OtlpReceiveCounter,
    private val producer: RawProducer,
) : MetricsServiceGrpc.MetricsServiceImplBase() {

    override fun export(request: ExportMetricsServiceRequest, responseObserver: StreamObserver<ExportMetricsServiceResponse>) {
        val metrics = request.resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { it.metricsCount } } // 메트릭 개수
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

// 트레이스와 같은 구조. 다른 점 = 세는 대상(로그 레코드 개수) · 신호 이름(LOGS) · 응답 타입
@Component
class OtlpLogsService(
    private val counter: OtlpReceiveCounter,
    private val producer: RawProducer,
) : LogsServiceGrpc.LogsServiceImplBase() {

    override fun export(request: ExportLogsServiceRequest, responseObserver: StreamObserver<ExportLogsServiceResponse>) {
        val records = request.resourceLogsList.sumOf { rl -> rl.scopeLogsList.sumOf { it.logRecordsCount } } // 로그 레코드 개수
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

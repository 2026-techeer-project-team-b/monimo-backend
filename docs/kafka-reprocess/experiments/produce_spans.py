#!/usr/bin/env python3
# 격리 Kafka(raw 토픽)에 OTLP 트레이스 레코드 하나를 넣는다. 수집기와 같은 형식: 키 "traces", 값 = ExportTraceServiceRequest 바이트.
# 사용: produce_spans.py <ok|error> <스팬 수> <버킷 분 시작 epoch초> <run-id>
import hashlib, sys
from kafka import KafkaProducer
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
from opentelemetry.proto.trace.v1.trace_pb2 import ResourceSpans, ScopeSpans, Span, Status
from opentelemetry.proto.resource.v1.resource_pb2 import Resource
from opentelemetry.proto.common.v1.common_pb2 import KeyValue, AnyValue

kind, n, minute, run = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
SERVICE = f"exp-reprocess-{run}"


def kv(k, v):
    return KeyValue(key=k, value=AnyValue(int_value=v) if isinstance(v, int) else AnyValue(string_value=v))


spans = []
for i in range(n):
    tag = f"{run}-{kind}-{i}"
    # 버킷 분 안의 1초~(n)초 지점. 같은 1분에 모두 들어간다
    start = (minute + 1 + i % 50) * 1_000_000_000
    spans.append(Span(
        trace_id=hashlib.sha256(tag.encode()).digest()[:16],
        span_id=hashlib.sha256((tag + "s").encode()).digest()[:8],
        name="GET /exp",
        kind=Span.SPAN_KIND_SERVER,
        start_time_unix_nano=start,
        end_time_unix_nano=start + 20_000_000,
        status=Status(code=Status.STATUS_CODE_ERROR if kind == "error" else Status.STATUS_CODE_OK),
        attributes=[kv("http.request.method", "GET"), kv("url.path", "/exp"),
                    kv("http.response.status_code", 500 if kind == "error" else 200)],
    ))

req = ExportTraceServiceRequest(resource_spans=[ResourceSpans(
    resource=Resource(attributes=[kv("service.name", SERVICE), kv("service.instance.id", "exp-1")]),
    scope_spans=[ScopeSpans(spans=spans)],
)])

p = KafkaProducer(bootstrap_servers="localhost:39092", acks="all")
md = p.send("raw", key=b"traces", value=req.SerializeToString()).get(timeout=10)
p.flush()
print(f"{kind}: {n} spans → raw-{md.partition}@{md.offset} service={SERVICE}")

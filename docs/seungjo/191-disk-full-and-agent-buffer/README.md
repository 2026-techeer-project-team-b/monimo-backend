# `#191` 디스크가 가득 찰 때(Q30)와 에이전트 버퍼 한계(Q31) : 재서 적었다

> 이슈 `#191` · 2026-10-14 · 승조 · 결정은 ADR `#68`
> 둘 다 "확인하고 적으면 끝" 인 질문이었다. 코드는 바꾸지 않았다.
> `02-open-questions.md` Q30 · Q31 · `30-failure-modes.md` 「아직 답하지 못하는 것」 두 줄 · `10-requirements.md` 유실 허용치가 이 둘에 걸려 있었다.

## 한 줄

**ClickHouse 가 가득 차면 살아서 거절하고(유실 아님), Kafka 가 가득 차면 브로커가 스스로 죽는다(수집이 멈춘다).** 그때 에이전트가 들고 버티는 것은 **서비스당 신호당 스팬 2048개**이고 그 이상은 사라진다.

---

## Q30 : 저장소가 가득 찰 때

### 어떻게 쟀나

격리 컨테이너에 **작은 tmpfs** 를 데이터 디렉터리로 물려 일부러 채웠다. 공유 compose 는 건드리지 않았다.

- ClickHouse : `clickhouse/clickhouse-server:26.8.10.6` · `/var/lib/clickhouse` 를 300MB tmpfs 로 · 우리 DDL(표 18개) 그대로 적용
- Kafka : `apache/kafka:3.9.2` · 로그 디렉터리를 200MB tmpfs 로 · 토픽 `raw` 파티션 1

### ClickHouse : 살아서 거절한다

| 적재 | 디스크 | 결과 |
|---|---|---|
| 30만 줄 × 3회 | 29% → 56% → 83% | 성공 |
| 30만 줄 (4회차) | 83% | **`Code: 243 ... Cannot reserve 99.85 MiB, not enough space. (NOT_ENOUGH_SPACE)`** |
| 5만 줄 | 93% → 98% | 성공 |
| 2만 줄 | 98% → 100% | 성공 |
| 1만 줄 | 100% (2.4M 남음) | `Code: 243 ... Cannot reserve 3.33 MiB` |
| 5천 줄 | 100% (880K 남음) | `Code: 243 ... Cannot reserve 1.00 MiB: while pushing to view monimo.mv_service_health_1m` |

알아 둘 것 셋

- **100% 가 되기 전에 거절한다.** ClickHouse 는 넣을 양만큼 **미리 예약**하므로 83% 에서 100MiB 예약이 거절됐다. 즉 "디스크가 꽉 찼다" 가 아니라 "이번 적재분을 예약할 수 없다" 다
- **적재 크기에 따라 통과와 거절이 섞인다.** 52MB 남았을 때 큰 적재(30만 줄)는 거절되고 작은 적재(54줄)는 통과했다. 그래서 포화는 **전부 실패가 아니라 띄엄띄엄 실패**로 시작한다
- 마지막 오류에 **`while pushing to view`** 가 붙었다. MV 쪽 적재에서도 같은 예약이 걸린다

### ClickHouse 포화를 우리 코드가 어떻게 보나

가득 찬 그 서버에 **적재 처리기를 실제로 붙여** 돌렸다.

```
raw-0@0 → raw.dlq (reason=transient, cause=Code: 243. DB::Exception:
          Cannot reserve 1.00 MiB, not enough space: While executing
          WaitForAsyncInsert. (NOT_ENOUGH_SPACE))
```

| 무엇 | 실측 |
|---|---|
| 분류 | **`transient`** (`FailureClassifier.TRANSIENT_CODES` 에 243 이 이미 있었다) |
| 재시도 간격 | **2.0 → 4.0 → 8.0 → 16.1초** (ADR `#51` 의 2초 시작 · 배수 2.0 · 상한 30초 그대로) |
| 기다림이 끝나면 | `raw.dlq` 로 보낸다. 기본 10분이라 그 전에 디스크가 비면 그대로 통과한다 |
| 유실인가 | **아니다.** `raw.dlq` 보관 30일 안에 되넣으면 된다 (되넣는 잡은 아직 없다) |

즉 **ClickHouse 포화는 이미 설계대로 흘러간다.** 고칠 것이 없다.

### Kafka : 스스로 죽는다

| 단계 | 결과 |
|---|---|
| 1KB × 20만 건 | 성공. 디스크 198M / 200M (99%) |
| 1KB × 3천 건 더 | 생산자가 `KAFKA_STORAGE_ERROR` 를 받는다 : `KafkaStorageException: Disk error when trying to access log file on the disk` |
| 생산자 거동 | **거의 무한 재시도** : `retrying (2147483646 attempts left)` (`retries` 기본값이 `Integer.MAX_VALUE` 라서다. 실제 한도는 `delivery.timeout.ms` 기본 2분) |
| 브로커 거동 | `Stopping serving replicas in dir /kdata ... because the log directory has failed` → **`Shutdown broker because all log dirs in /kdata have failed`** → 컨테이너 종료 |

**이것이 Q30 의 가장 중요한 답이다.** Kafka 포화는 "거절" 로 끝나지 않고 **끊김으로 바뀐다.** 우리가 2026-10-04 에 이미 재현한 그 경로(끊긴 Kafka)로 합쳐진다.

### 그때 보낸 쪽이 받는 것 : 적어 둔 것이 틀렸다

죽은 Kafka 를 가리키는 수집기를 띄우고 가짜 발신기로 쏘았다.

| | |
|---|---|
| `AGENTS.md` §6 에 적혀 있던 것 | "에이전트에 **`UNAVAILABLE`** 로 돌려보내므로 유실은 아니고 수집이 안 되는 상태다" |
| 실측 | 보낸 쪽이 받는 것은 **`code = DeadlineExceeded` (context deadline exceeded)** 다 |

수집기 쪽 로그는 `Bootstrap broker localhost:39092 (id: -1) disconnected` 를 1초마다 반복한다. **수집기는 응답을 아예 주지 않고**, 끝내는 쪽은 **보낸 쪽의 시간 제한**이다(에이전트 기본 30초 · 가짜 발신기 10초).

왜 달랐나 : **코드는 `UNAVAILABLE` 을 돌려준다**(`OtlpResponder.kt`). 틀린 것은 그 응답에 **닿는다는 전제**였다. 숫자로 보면 이렇다.

| 경우 | 발행이 실패하는 데 걸리는 시간 | 보낸 쪽이 받는 것 |
|---|---|---|
| 토픽이 없다 | 즉시 (`UNKNOWN_TOPIC_OR_PARTITION`) | **`UNAVAILABLE`** (수집기가 돌려준다) |
| 브로커가 죽었다 · 가득 찼다 | **최대 2분** (`delivery.timeout.ms` 기본값) | **`DeadlineExceeded`** (에이전트 전송 제한 30초가 먼저 끊는다) |

두 경우를 하나로 적어 둔 것이 틀렸다. `2분 > 30초` 라서 뒤쪽은 **수집기의 오류 응답에 영원히 닿지 못한다.**

차이가 중요한 이유 : 오류를 돌려받으면 에이전트가 **바로 다음 배치를 준비**하지만, 응답이 없으면 **30초를 기다린다.** 그 30초 동안 큐가 계속 차므로 버림이 빨라진다.

---

## Q31 : 에이전트 버퍼 한계

### 기본값은 우리가 쓰는 jar 에서 직접 읽었다

최신 문서의 기본값을 가정하지 않았다(`#119` 에서 같은 실수를 했다). 쇼핑몰 이미지에서 꺼낸 `opentelemetry-javaagent.jar`(`Implementation-Version: 2.31.1`)의 상수다.

| 설정 | 스팬 (`BatchSpanProcessor`) | 로그 (`BatchLogRecordProcessor`) |
|---|---|---|
| 큐 크기 | **2048** | 2048 |
| 한 묶음 | 512 | 512 |
| 보내는 주기 | 5000ms | 1000ms |
| 전송 시간 제한 | 30000ms | 30000ms |

지표(`PeriodicMetricReader`)는 **큐가 없다.** 주기마다 그 시점 값을 밀어 넣고 실패하면 그 주기분이 사라진다. 우리는 주기를 10초로 줄여 뒀다(`agent.properties`).

재전송 정책(`RetryPolicy`) 기본값 : **최대 5회 · 시작 1초 · 상한 5초 · 배수 1.5**

우리는 `agent.properties` 에 이 중 **아무것도 설정하지 않았다.** 전부 기본값이다.

### 실제로 얼마나 사라지나

수집기를 멈추고 쇼핑몰에 요청을 넣어 버퍼를 넘긴 뒤 살려서, 수집기 카운터로 도착분을 셌다. 카운터를 쓴 이유는 **샘플링을 피하려고**다 : ClickHouse 줄 수로 세면 1% 만 보인다.

| 무엇 | 값 |
|---|---|
| 요청당 스팬 | **13.24개** (요청 50건에 662 스팬, 수집기 정상 상태에서 실측) |
| 수집기 중단 시간 | 약 50초 (05:20:08 멈춤 → 05:20:59 복귀) |
| 그 사이 요청 | 1,500건 (24초에 보냈다) |
| 생산한 스팬 | 약 **19,860개** |
| 살아 온 스팬 | **2,084개** |
| 사라진 스팬 | 약 **17,776개 = 89.5%** |

에이전트 쪽 로그

| 서비스 | `Failed to export spans` |
|---|---|
| gateway | 5회 |
| order | 6회 |
| inventory | 5회 |
| payment | 4회 |

스택에 `io.opentelemetry.exporter.sender.okhttp.internal.RetryInterceptor` 가 들어 있다 : **재전송이 실제로 돈다.** 지표 쪽은 `PeriodicMetricReader - Exporter failed` 가 따로 찍힌다.

### 2048 × 4 = 8192 가 아니라 2,084 인 이유

도착분은 큐 상한의 합(8192)의 **25%** 다. 두 가지가 겹쳤다.

- **네 서비스가 고르게 만들지 않는다.** 재고가 바닥나 주문이 `inventory error` 로 실패했고, 그래서 payment 로 가는 호출이 적다. 서비스별 생산량이 다르다
- **내보내려다 실패한 묶음은 그대로 사라진다.** 큐에 남아 있는 것만 살아남는다. 5초마다 512개씩 내보내려 하므로 50초면 10번 시도 = 최대 5,120개가 **큐 밖에서** 버려진다

그래서 **"큐 2048" 은 상한이고 실제로 살아 오는 것은 그보다 적다.** 요구사항에 적을 숫자는 "최대 2048" 과 "실측 89.5% 유실" 둘이다.

### 서비스별로 쪼개지 못한 것

도착분을 서비스별로 가르지 못했다. 수집기 카운터에 서비스 꼬리표가 없고, ClickHouse 는 샘플링 1% 라 줄이 20개쯤밖에 안 된다. 쪼개려면 샘플링을 1.0 으로 올려야 하는데 공유 PG 를 바꾸는 일이라 하지 않았다. **합계로만 쟀다.**

---

## 이번에 바뀐 판단

| 전 | 후 |
|---|---|
| "저장소 포화 때 거동을 모른다" | ClickHouse 는 243 으로 거절 → `transient` → DLQ (유실 아님) · Kafka 는 **브로커 자살** → 끊김 |
| "에이전트에 `UNAVAILABLE` 로 돌려보낸다" | **틀렸다.** 토픽 없음일 때만 그렇고, 브로커가 죽거나 가득 차면 **응답이 없고 보낸 쪽이 시간 제한으로 끊는다** |
| "버퍼가 넘치면 사라진다 (한계 모름)" | 큐 **2048/서비스/신호** · 50초 중단에 **89.5% 유실** 실측 |
| "포화는 재시도 뒤 같은 길로 가나" | ClickHouse 는 같은 길(끊김과 같은 `transient`). **Kafka 는 다른 길**(자살) |

## 안 고친 것과 그 이유

**아무 코드도 바꾸지 않았다.** 이유는 ADR `#68` 에 적었다. 요약하면

- ClickHouse 포화는 **이미 설계대로** 흘러간다 (243 → `transient` → DLQ)
- Kafka 자살은 고치려면 **디스크 감시 · 보관 상한 · 경보**가 붙고 그 셋이 배포 단계의 일이다
- 수집기가 발행 실패에 응답을 안 주는 것은 고칠 자리가 있지만(발행 시간 제한을 짧게 끊기), **지금 그 값을 정할 근거가 없다**. 에이전트 전송 제한 30초와 맞춰야 하고 그 숫자는 운영 실측이 필요하다

## 재현 방법

이 문서의 숫자를 다시 만들려면

```
# ClickHouse 포화
docker run -d --name exp-full-ch -p 28124:8123 -e CLICKHOUSE_USER=monimo -e CLICKHOUSE_PASSWORD=monimo \
  --mount type=tmpfs,destination=/var/lib/clickhouse,tmpfs-size=300m clickhouse/clickhouse-server:26.8.10.6
# db/clickhouse/V*.sql 적용 후 큰 INSERT 를 반복한다

# Kafka 포화
docker run -d --name exp-full-kafka -p 39092:39092 ... -e KAFKA_LOG_DIRS=/kdata \
  --mount type=tmpfs,destination=/kdata,tmpfs-size=200m apache/kafka:3.9.2
docker exec exp-full-kafka /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic raw --num-records 200000 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers=localhost:39092 acks=1

# 에이전트 버퍼
docker compose stop collector
# 쇼핑몰에 요청 1500건
docker compose start collector
curl -s localhost:18081/actuator/metrics/monimo.collector.otlp.received?tag=signal:traces
```

에이전트 기본값은 jar 에서 읽는다 : 이미지에서 `/app/otel/opentelemetry-javaagent.jar` 를 꺼내 `inst/io/opentelemetry/sdk/trace/export/BatchSpanProcessorBuilder.classdata` 를 `javap -p -constants` 로 본다.

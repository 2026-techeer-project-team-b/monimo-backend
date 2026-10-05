package com.monimo.ingester.inbound.kafka // 들어오는 문(inbound) : Kafka 쪽

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 적재 실패 때 얼마나 기다리나. application.yml 의 monimo.ingester.retry.* 를 읽는다 (ADR #51).
//
// 실패를 세 종류로 갈라 각각 다른 시간만큼 버틴다. 모양은 셋 다 같고(2초 시작 · 2배씩 · 한 번 최대 30초)
// 총 시간만 다르다. 시간이 다 차면 복구 담당이 불려 raw.dlq 로 보낸다.
//
//   확실한 일시 장애  transient-max-elapsed  10분   ClickHouse 가 닿지 않음 · 라이브러리가 "재시도해도 됨" 이라 한 코드
//   모르는 실패       unknown-max-elapsed     1분   위도 아래도 아닌 것. 틀려도 안전한 쪽(재시도)으로 기울인다
//   확실한 독성       (설정 없음)              0    protobuf 못 풂 · 데이터가 틀린 코드. 재시도 없이 바로 DLQ
//
// 10분인 이유: 컨테이너 재시작 10~30초 · 파드 재배치 1~2분 · 노드 장애 재스케줄 3~5분을 다 견디는 선이다.
//   그보다 긴 장애는 대개 사람이 손을 써야 하는 일이라 DLQ 에 쌓이게 해 신호로 쓴다.
// 1분인 이유: 모르는 실패가 진짜 독성이면 그동안 그 파티션이 막히므로, 짧은 네트워크 끊김만 살려 내는 선에서 끊는다.
//
// 환경변수로 덮어쓴다: MONIMO_INGESTER_RETRY_TRANSIENT_MAX_ELAPSED=20m 같은 식 (스프링 Duration 표기)
@ConfigurationProperties("monimo.ingester.retry")
data class RetryProperties(
    val initialInterval: Duration = Duration.ofSeconds(2), // 첫 대기
    val multiplier: Double = 2.0, // 다음 대기 = 이전 대기 × 이 값
    val maxInterval: Duration = Duration.ofSeconds(30), // 한 번 대기의 상한. 이 뒤로는 30초마다
    val transientMaxElapsed: Duration = Duration.ofMinutes(10), // 확실한 일시 장애에 버티는 총 시간
    val unknownMaxElapsed: Duration = Duration.ofMinutes(1), // 모르는 실패에 버티는 총 시간
    val dlqTopic: String = "raw.dlq", // 복구 담당이 보내는 곳. compose.yaml 이 만든다 (파티션 1 · 30일)
)

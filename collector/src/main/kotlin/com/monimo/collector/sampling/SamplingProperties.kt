package com.monimo.collector.sampling

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 트레이스 샘플링 설정. application.yml 의 monimo.collector.sampling.* 를 읽는다.
//
// ratio = PG 를 못 읽었을 때 쓸 비율. **정본이 아니다.**
//   정본은 PG application_configs.sampling_rate 이고 수집기가 30초마다 읽는다 (ADR #33 · #53).
//   읽는 데 성공하면 PG 가 이기고, 한 번도 못 읽었으면 이 값으로 돈다.
//   빈 값을 쓰지 않는 이유: 0 이면 전부 버리고 1 이면 전부 통과시켜 둘 다 사고다.
//   환경변수 MONIMO_COLLECTOR_SAMPLING_RATIO 로 덮어쓸 수 있는데 그것도 이 기본값 자리를 바꾸는 것뿐이다.
// ttl = PG 를 다시 읽는 주기. 화면에서 비율을 바꾸면 최대 이만큼 뒤에 반영된다 (ADR #37).
//   테스트에서 짧게 쓰려고 설정으로 뺐다. Jaeger · OTel 은 60초, Elastic APM 서버 기본값은 30초다.
// canaryMarker = 파수꾼이 붙이는 표시. span 의 trace_state 에 이 항목이 있으면 비율과 무관하게 통과시킨다 (ADR #41)
@ConfigurationProperties("monimo.collector.sampling")
data class SamplingProperties(
    val ratio: Double = 0.01,
    val ttl: Duration = Duration.ofSeconds(30),
    val canaryMarker: String = "monimon=canary",
)

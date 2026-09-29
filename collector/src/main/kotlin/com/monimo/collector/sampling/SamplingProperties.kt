package com.monimo.collector.sampling

import org.springframework.boot.context.properties.ConfigurationProperties

// 트레이스 샘플링 설정. application.yml 의 monimo.collector.sampling.* 를 읽는다.
//
// ratio = 남길 비율. 0.01 이면 100개 중 1개(정확히는 trace ID 해시가 하위 1% 구간에 드는 것)만 남긴다.
//   기본 1% 는 ADR #33. 로컬 프로필에서는 1.0(전부 통과)으로 덮어써서 흐름을 눈으로 본다.
//   다음 이슈에서 이 값을 PG(application_configs)에서 읽어 30초마다 갱신한다. 그때도 이 클래스가 기본값 역할을 한다.
// canaryMarker = 파수꾼이 붙이는 표시. span 의 trace_state 에 이 항목이 있으면 비율과 무관하게 통과시킨다 (ADR #41)
@ConfigurationProperties("monimo.collector.sampling")
data class SamplingProperties(
    val ratio: Double = 0.01,
    val canaryMarker: String = "monimon=canary",
)

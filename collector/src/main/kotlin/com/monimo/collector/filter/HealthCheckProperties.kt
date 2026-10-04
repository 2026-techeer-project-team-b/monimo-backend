package com.monimo.collector.filter

import org.springframework.boot.context.properties.ConfigurationProperties

// 헬스체크 스팬 거르기 설정. application.yml 의 monimo.collector.health-check.* 를 읽는다.
//
// paths = 헬스체크로 볼 주소 목록. 스팬의 url.path 가 이 중 하나와 정확히 같으면(SERVER 스팬일 때만) 버린다.
//   기본값 /actuator/health 는 쇼핑몰 4개가 공통으로 여는 주소다. 환경변수로 덮어쓴다:
//   MONIMO_COLLECTOR_HEALTH_CHECK_PATHS=/actuator/health,/healthz  (쉼표로 구분)
//
//   비워 두면 아무것도 버리지 않는다. 필터를 끄는 방법이고, 머지 전후 숫자를 비교하거나
//   헬스체크 자체를 조사할 때 쓴다 (버린 스팬은 Kafka 에 안 들어가 영영 못 보기 때문).
//
// 왜 앱별(PG)이 아니라 수집기 전역 env 인가: 쇼핑몰 4개가 전부 Spring Boot 라 주소가 같고,
//   앱별로 다르게 하려면 API 문 · 설정 화면 · 30초 캐시가 전부 필요하다. 로그 하한을 전역 env 로
//   정한 것과 같은 성격이다 (ADR #38). 샘플링 비율이 PG 로 옮겨가면(K) 그 길에 같이 태울 수 있다.
@ConfigurationProperties("monimo.collector.health-check")
data class HealthCheckProperties(
    val paths: Set<String> = setOf("/actuator/health"),
)

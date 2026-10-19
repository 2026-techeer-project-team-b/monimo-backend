package com.monimo.collector.filter

import org.springframework.boot.context.properties.ConfigurationProperties

// 로그 하한 설정. application.yml 의 monimo.collector.log.* 를 읽는다 (ADR #38 ④ · #55 되돌림 ③, #215).
//
// min-level = 이 등급 미만의 로그 레코드를 버린다. TRACE · DEBUG · INFO · WARN · ERROR · FATAL 중 하나, 기본 INFO.
//   환경변수로 덮어쓴다: MONIMO_COLLECTOR_LOG_MIN_LEVEL=WARN
//   비워 두면 아무것도 버리지 않는다. 헬스체크 거르기와 같은 약속(끄는 방법 = 빈 값)이다.
//
// 왜 수집기가 거르나: 에이전트는 logback 이 내보낸 등급을 그대로 보내고 등급 하한 옵션이 없다(logback-appender
//   공식 설정에 수준 항목 없음). 보내는 쪽 yml 을 고치는 것은 우리 모듈에만 통하고 남의 앱에는 안 통한다.
//   받는 쪽에서 막아야 "에이전트만 붙이면 된다"(ADR #33)가 지켜진다
@ConfigurationProperties("monimo.collector.log")
data class LogLevelProperties(
    val minLevel: String = "INFO",
)

package com.monimo.api.selfcheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 자기 점검 설정 (application.yml monimo.self-check). 게이지는 관리 포트(업무 포트 + 10000, ADR #61)에 있다
@ConfigurationProperties("monimo.self-check")
data class SelfCheckProperties(
    val detectorManagementUrl: String = "http://localhost:18083",
    val collectorManagementUrl: String = "http://localhost:18081",
    // PG 두드림이 이보다 오래 걸리면 실패로 친다. pgjdbc socketTimeout 과 같은 값 (ADR #54)
    val postgresTimeout: Duration = Duration.ofSeconds(10),
    // 낡음 게이지가 이보다 크면 멈춘 것으로 친다. 탐지는 1분 주기 3번, 수집기는 30초 주기 6번
    val detectorMaxAge: Duration = Duration.ofSeconds(180),
    val collectorMaxAge: Duration = Duration.ofSeconds(180),
    val gaugeTimeout: Duration = Duration.ofSeconds(2),
)

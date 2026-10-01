package com.monimo.ingester.transform

import java.time.Instant

// ClickHouse logs 표 한 줄 = 로그 레코드 하나
data class LogRow(
    val traceId: String, // 요청과 잇는 번호. 없으면 '' (콜트리에서 "이 요청이 남긴 로그" 로 넘어가는 끈)
    val spanId: String, // 같은 요청 안에서 어느 구간이 남겼는지. 없으면 ''
    val ts: Instant, // 기록 시각. CH 컬럼은 DateTime64(3) 이라 밀리초까지 남는다
    val serviceName: String,
    val agentId: String,
    val logger: String, // 기록한 클래스 이름. 예: com.monimo.shop.order.OrderService
    val thread: String, // 스레드 이름. 예: http-nio-8080-exec-3. 없으면 ''
    val level: String, // TRACE · DEBUG · INFO · WARN · ERROR · FATAL
    val message: String, // 사람이 읽는 본문
    val attributes: Map<String, String>, // 코드가 로그마다 붙인 꼬리표(MDC) 묶음
)

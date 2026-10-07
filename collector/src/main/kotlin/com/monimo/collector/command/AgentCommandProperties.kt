package com.monimo.collector.command

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 스레드 덤프 명령 문 설정. application.yml 의 monimo.collector.agent.* 를 읽는다 (#122, monimo-shop #34).
//
// token          = 쇼핑몰 Extension 이 /agent/** 에 붙이는 X-Monimo-Agent-Token 값 (MONIMO_AGENT_TOKEN)
// internal-token = API 서버가 /internal/** 에 붙이는 X-Internal-Token 값 (MONIMO_INTERNAL_TOKEN, API 서버 내부 문과 같은 값)
//   둘 다 비우면 그 문이 닫힌다. 내부 토큰을 쇼핑몰에 넣지 않으려고 둘을 나눴다 (합의안 4 : 감시 대상 앱이 털려도 내부 문은 안 열린다)
// advertised-url = 명령의 reply_to 에 넣는 이 수집기 자신의 주소. Extension 이 결과를 Service 이름이 아니라 여기로 보낸다 (합의안 2).
//   로컬 compose 는 http://collector:8081, K8s 는 헤드리스 서비스의 파드 주소 (MONIMO_COLLECTOR_ADVERTISED_URL)
// poll-timeout   = 에이전트 폴링을 붙잡는 시간. Extension 의 요청 타임아웃(30초)보다 짧아야 한다
// gap-grace      = 폴링이 끝나고 다음 폴링이 오기 전 빈틈. 이 안에 온 명령은 보관했다 다음 폴링에 준다 (합의안 3)
@ConfigurationProperties("monimo.collector.agent")
data class AgentCommandProperties(
    val token: String = "",
    val internalToken: String = "",
    val advertisedUrl: String = "http://collector:8081",
    val pollTimeout: Duration = Duration.ofSeconds(25),
    val gapGrace: Duration = Duration.ofSeconds(2),
    val defaultDumpTimeout: Duration = Duration.ofSeconds(10),
    val maxDumpTimeout: Duration = Duration.ofSeconds(30),
)

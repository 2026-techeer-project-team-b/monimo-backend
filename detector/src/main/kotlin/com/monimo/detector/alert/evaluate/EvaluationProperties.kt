package com.monimo.detector.alert.evaluate

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 스케줄 평가 설정. 값은 임시 — 수집 → 조회 지연을 실측한 뒤 조정한다
@ConfigurationProperties("monimo.alert.schedule")
data class EvaluationProperties(
    // 스케줄러를 켤지. 테스트는 끄고 EvaluationRunner.runOnce 를 직접 부른다
    val enabled: Boolean = true,
    // 평가 주기 (ADR #39: 규칙 폴링 10~30초)
    val interval: Duration = Duration.ofSeconds(15),
    // 1분 버킷이 끝나고 이만큼 지나야 "완료"로 보고 판정한다. 적재가 덜 끝난 분을 정상으로 오판하지 않으려는 것
    val settleDelay: Duration = Duration.ofSeconds(30),
    // 이보다 오래된 버킷은 판정하지 않는다. 멈췄다 다시 돌 때 따라잡는 범위이기도 하다
    val maxStaleness: Duration = Duration.ofMinutes(3),
    val query: Query = Query(),
) {
    data class Query(
        // API 서버 주소 (내부 문 service-health)
        val baseUrl: String = "http://localhost:8080",
        val connectTimeout: Duration = Duration.ofSeconds(1),
        val requestTimeout: Duration = Duration.ofSeconds(3),
    )
}

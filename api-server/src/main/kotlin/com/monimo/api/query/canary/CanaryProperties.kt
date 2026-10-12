package com.monimo.api.query.canary

import org.springframework.boot.context.properties.ConfigurationProperties

// 카나리 판정 기준 (application.yml monimo.canary)
@ConfigurationProperties("monimo.canary")
data class CanaryProperties(
    // 가장 최근 카나리가 이 초보다 오래됐으면 fresh = false. 파수꾼은 1분마다 쏘고 직전 주기 것을 본다
    val thresholdSec: Long = 180,
)

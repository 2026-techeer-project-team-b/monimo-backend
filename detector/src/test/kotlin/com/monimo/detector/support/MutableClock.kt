package com.monimo.detector.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// 테스트가 시각을 직접 움직이는 시계
class MutableClock(@Volatile var now: Instant) : Clock() {
    fun advance(d: Duration) { now = now.plus(d) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

package com.monimo.api.query.support

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 스팬 시각 표기. 나노초 9자리 고정이라 글자 순서가 시간 순서와 같다 (화면이 글자로 정렬한다)
object NanoTime {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)
    private val MILLIS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(epochNanos: Long): String = FORMAT.format(Instant.ofEpochSecond(0, epochNanos))

    // transactions 처럼 ms 까지만 있는 시각은 3자리 고정
    fun formatMillis(epochMillis: Long): String = MILLIS_FORMAT.format(Instant.ofEpochMilli(epochMillis))
}

package com.monimo.api.query.support

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 스팬 시각 표기. 나노초 9자리 고정이라 글자 순서가 시간 순서와 같다 (화면이 글자로 정렬한다)
object NanoTime {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun format(epochNanos: Long): String = FORMAT.format(Instant.ofEpochSecond(0, epochNanos))
}

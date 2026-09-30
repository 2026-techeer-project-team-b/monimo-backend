package com.monimo.api.alert

import java.time.Instant
import java.time.temporal.ChronoUnit

// 알림 표에 넣는 시각. PG timestamptz 는 마이크로초까지만 저장하므로 미리 잘라 둔다.
// 안 자르면 Linux(나노초)에서 방금 준 응답과 다시 읽은 값이 달라진다 (#41 CI)
object DbTime {
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}

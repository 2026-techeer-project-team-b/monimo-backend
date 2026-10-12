package com.monimo.ingester.transform

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

// ClickHouse metrics_raw 표 한 줄 = 데이터 포인트 하나 (long 형식, ADR #38).
// "jvm.memory.used 가 12:00:15 에 G1 Old Gen 에서 300MB 였다" 가 한 줄이다.
// 이름이 어떤 지표가 오든 컬럼을 안 바꿔도 되는 것이 이 모양의 장점이다.
data class MetricRow(
    val serviceName: String, // resource 의 service.name. PG applications.name 과 같은 글자여야 화면이 잇는다
    val agentId: String, // 파드 식별자 (SpanRow 와 같은 규칙)
    val metricName: String, // 예: jvm.memory.used. 히스토그램은 jvm.gc.duration.count 처럼 접미가 붙는다
    val attributes: Map<String, String>, // 같은 지표를 갈래로 나누는 꼬리표. 예: jvm.memory.pool.name=G1 Old Gen
    val ts: Instant, // 측정 시각. CH 컬럼은 DateTime(초) 이라 넣을 때 초 아래는 잘린다
    val value: Double, // 측정값
) {
    // attributes 주머니를 숫자 하나로 접은 값. CH 는 Map 을 정렬 키에 못 넣는데
    // 'G1 Old' 와 'G1 Eden' 은 다른 줄로 갈라야 해서 이 숫자를 정렬 키에 넣는다.
    // 같은 attributes 면 언제 계산해도 같은 숫자가 나와야 하므로 키를 정렬해 글자를 고정한 뒤 해시한다.
    // UInt64 컬럼이라 ULong. Long 으로 두면 상위 비트가 1 일 때 음수가 되어 CH 가 거부한다
    val seriesHash: ULong = seriesHashOf(attributes)

    companion object {
        // 가짜 데이터(scripts/seed/clickhouse-fake-signals.sql)는 CH 의 cityHash64(toString(map)) 로 만들어 이 값과 다르다.
        // 이 숫자는 "같은 지표 안에서 갈래를 나누는" 용도라 두 출처가 한 그래프에 섞이지 않으면 문제 없다
        // (seed-clickhouse.sh 는 표를 비우고 넣는다)
        fun seriesHashOf(attributes: Map<String, String>): ULong {
            val canonical = attributes.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return ByteBuffer.wrap(digest, 0, 8).long.toULong() // 앞 8바이트만 숫자로
        }
    }
}

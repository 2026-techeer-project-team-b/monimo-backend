package com.monimo.api.query.support

// 켜진 뒤 총합(is_counter = 1)을 칸마다 늘어난 양으로 바꾼다 (ADR #68). 한 파드 · 한 시계열의 시간순 값만 넘긴다.
// 첫 칸은 앞 값(prev)이 없으면 버리고, 뺀 값이 음수면 재시작(총합이 0 부터 다시)이라 그 칸도 버린다
object CounterDelta {

    fun <T> of(points: List<T>, prev: Double?, value: (T) -> Double): List<Pair<T, Double>> {
        var before = prev
        val result = mutableListOf<Pair<T, Double>>()
        for (point in points) {
            val current = value(point)
            before?.let { if (current >= it) result += point to current - it }
            before = current
        }
        return result
    }
}

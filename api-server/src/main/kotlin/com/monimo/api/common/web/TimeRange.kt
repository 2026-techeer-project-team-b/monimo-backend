package com.monimo.api.common.web

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import java.time.Duration
import java.time.Instant

// 조회 시간 범위 from(포함) ~ to(제외) (API 명세 §0). of() 를 거쳐야만 만들어지므로 늘 검사를 통과한 값이다
class TimeRange private constructor(
    val from: Instant,
    val to: Instant,
) {
    val duration: Duration
        get() = Duration.between(from, to)

    companion object {
        // 명세에 상한이 없어 화면 리뷰의 "최대 7일" 을 기본으로 쓴다. 더 긴 범위가 필요한 API 는 max 를 넘긴다
        val DEFAULT_MAX: Duration = Duration.ofDays(7)

        fun of(from: Instant, to: Instant, max: Duration = DEFAULT_MAX): TimeRange {
            if (!from.isBefore(to)) {
                throw ApiException(ErrorCode.UNPROCESSABLE, "from 은 to 보다 앞이어야 합니다.")
            }
            if (Duration.between(from, to) > max) {
                throw ApiException(ErrorCode.TIME_RANGE_TOO_WIDE, "시간 범위는 최대 ${max.toDays()}일입니다.")
            }
            return TimeRange(from, to)
        }
    }
}

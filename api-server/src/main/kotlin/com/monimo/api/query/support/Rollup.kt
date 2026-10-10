package com.monimo.api.query.support

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode

// step(초)에 따라 읽을 표의 단위 (API 명세 §0 · FN-47). 60 미만 원본, 60 이상 1분 롤업, 3600 이상 1시간 롤업
enum class Rollup {
    RAW,
    MINUTE,
    HOUR,
    ;

    companion object {
        const val DEFAULT_STEP = 60

        fun of(step: Int): Rollup {
            if (step <= 0) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "step 은 1 이상이어야 합니다.")
            }
            return when {
                step >= 3600 -> HOUR
                step >= 60 -> MINUTE
                else -> RAW
            }
        }

        // 재료가 1분 단위인 API(service-health · errors/timeline)는 1분보다 잘게 나눌 수 없다
        fun requireMinuteStep(step: Int): Int {
            if (step < 60 || step % 60 != 0) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "step 은 60 이상 60 의 배수여야 합니다.")
            }
            return step
        }
    }
}

package com.monimo.api.query.trace

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.query.trace.dto.TraceResponse
import org.springframework.stereotype.Service

// 지워진 트레이스(93일)와 처음부터 없던 트레이스는 구분할 수 없어 둘 다 NOT_FOUND 로 응답한다 (#50, 팀 결정 대기)
@Service
class TraceService(
    private val traceRepository: TraceRepository,
) {

    fun get(traceId: String): TraceResponse {
        val id = traceId.lowercase()
        if (!TRACE_ID.matches(id)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "traceId 는 16진수 32글자여야 합니다.")
        }
        val spans = traceRepository.findSpans(id)
        if (spans.isEmpty()) {
            throw ApiException(ErrorCode.NOT_FOUND, "이 trace_id 의 트레이스를 찾을 수 없습니다.")
        }
        return SpanTree.assemble(id, spans)
    }

    private companion object {
        // W3C trace ID. 적재 처리기가 소문자 16진수로 저장한다
        val TRACE_ID = Regex("[0-9a-f]{32}")
    }
}

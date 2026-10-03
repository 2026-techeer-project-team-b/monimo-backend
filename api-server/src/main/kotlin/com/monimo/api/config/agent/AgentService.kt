package com.monimo.api.config.agent

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.config.ApplicationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 파드 목록 · 상세. 읽기만 한다 — 등록은 적재 처리기, status 갱신은 탐지 (ADR #39)
@Service
class AgentService(
    private val agents: AgentRepository,
    private val applications: ApplicationRepository,
) {
    // 목록 커서. agent_key 가 UK 라 위치로 충분하다
    data class Cursor(val agentKey: String)

    @Transactional(readOnly = true)
    fun list(serviceName: String?, status: String?, cursor: String?, limit: Int): ApiResponse<List<AgentResponse>> =
        page(serviceName, statusOf(status), cursor, limit)

    @Transactional(readOnly = true)
    fun get(agentUuid: UUID): AgentResponse =
        agents.findOne(agentUuid)?.let(AgentResponse::from)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "파드를 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun listOf(applicationUuid: UUID, status: String?, cursor: String?, limit: Int): ApiResponse<List<AgentResponse>> {
        // 서비스가 없거나 제외됐으면 빈 목록이 아니라 404 다 (명세 14번)
        val app = applications.findByApplicationUuidAndDeletedAtIsNull(applicationUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "서비스를 찾을 수 없습니다.")
        return page(app.name, statusOf(status), cursor, limit)
    }

    private fun page(serviceName: String?, status: String?, cursor: String?, limit: Int): ApiResponse<List<AgentResponse>> {
        val after = cursor?.let { CursorCodec.decode<Cursor>(it).agentKey }
        val rows = agents.findPage(serviceName, status, after, PageRequest.of(0, limit + 1)).map(AgentResponse::from)
        return CursorCodec.page(rows, limit) { Cursor(it.agentKey) }
    }

    private fun statusOf(value: String?): String? {
        if (value != null && value !in STATUSES) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "status 는 ${STATUSES.joinToString(" · ")} 중 하나여야 합니다.")
        }
        return value
    }

    private companion object {
        // agents 표의 CHECK 제약과 같은 값 (ADR #39 — 탐지가 90초 무신호면 DOWN 으로 바꾼다)
        val STATUSES = setOf("UP", "DOWN", "UNKNOWN")
    }
}

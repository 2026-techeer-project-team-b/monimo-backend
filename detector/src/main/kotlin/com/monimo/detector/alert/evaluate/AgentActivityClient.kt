package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

// API 서버 내부 문 GET /api/v1/internal/agents/active 의 한 줄 (API 명세 40번, 조회 파트 구현).
// 파드 키마다 한 줄, last_signal_at = spans · metrics_raw 중 더 최근 시각. agent_key 는 agents.agent_key 와 같은 글자
data class AgentSignal(
    val serviceName: String,
    val agentKey: String,
    val lastSignalAt: Instant,
)

// 조회 계약이 바뀌면 이 인터페이스 구현만 고친다. 판정 쪽은 그대로
fun interface AgentActivityClient {
    // [from, to) 에 데이터를 보낸 파드 키 전체 (service_name 없이 = 모든 서비스). 실패하면 예외 (빈 목록으로 삼키지 않는다)
    fun fetchActive(from: Instant, to: Instant): List<AgentSignal>
}

// X-Internal-Token 으로 인증한다. 스스로 재시도하지 않는다 — 다음 주기가 곧 재시도다
class HttpAgentActivityClient(
    private val props: EvaluationProperties.Query,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : AgentActivityClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetchActive(from: Instant, to: Instant): List<AgentSignal> {
        val request = HttpRequest.newBuilder(URI.create("${props.baseUrl.trimEnd('/')}$PATH?from=$from&to=$to"))
            .timeout(props.requestTimeout)
            .header("X-Internal-Token", internalToken)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw AgentActivityQueryException("agents/active 호출 실패: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) {
            throw AgentActivityQueryException("agents/active 응답 ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body())["data"].map {
            AgentSignal(it["service_name"].asText(), it["agent_key"].asText(), Instant.parse(it["last_signal_at"].asText()))
        }
    }

    private companion object {
        const val PATH = "/api/v1/internal/agents/active"
    }
}

class AgentActivityQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

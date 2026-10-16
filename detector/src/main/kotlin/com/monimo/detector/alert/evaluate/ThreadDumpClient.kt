package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

// 자동 스레드 덤프 결과. skipped = 그 파드의 직전 덤프가 5분 쿨다운 안이라 새로 뜨지 않았다 (에러가 아니다)
data class AutoDumpResult(
    val skipped: Boolean,
    val dumpUuid: String?,
)

// API 서버 내부 문 POST /api/v1/internal/thread-dumps (API 명세 4-1, 재범 #164 구현). 덤프를 뜨고 CH thread_dumps 에 저장하는 일은 API 서버가 한다
fun interface ThreadDumpClient {
    // 실패(연결 없음 · 503 · 504 · 시간 초과)는 예외. 부르는 쪽이 삼켜서 경보에 영향을 주지 않는다
    fun requestAuto(agentUuid: UUID): AutoDumpResult
}

// X-Internal-Token 으로 인증한다. 재시도하지 않는다 — 덤프는 경보가 열린 그 순간의 모습이라 늦게 다시 뜨면 의미가 다르다
class HttpThreadDumpClient(
    private val baseUrl: String,
    private val props: EvaluationProperties.ThreadDump,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : ThreadDumpClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun requestAuto(agentUuid: UUID): AutoDumpResult {
        val body = objectMapper.writeValueAsString(mapOf("agent_uuid" to agentUuid.toString(), "requested_by" to REQUESTED_BY))
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}$PATH"))
            .timeout(props.requestTimeout)
            .header("X-Internal-Token", internalToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw ThreadDumpRequestException("thread-dumps 호출 실패: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) {
            throw ThreadDumpRequestException("thread-dumps 응답 ${response.statusCode()}")
        }
        val data = objectMapper.readTree(response.body())["data"]
        return AutoDumpResult(
            skipped = data["skipped"].asBoolean(),
            dumpUuid = data["dump_uuid"]?.takeUnless { it.isNull }?.asText(),
        )
    }

    private companion object {
        const val PATH = "/api/v1/internal/thread-dumps"
        const val REQUESTED_BY = "detector"
    }
}

class ThreadDumpRequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

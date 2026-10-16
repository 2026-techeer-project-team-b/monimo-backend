package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

// API 서버 내부 문 GET /api/v1/internal/metrics/gc-time 의 한 줄 (API 명세 51번, 조회 #197 구현).
// 파드 하나의 1분 동안 GC 에 쓴 시간(ms)과 횟수. 켜진 뒤 총합을 빼는 일 · 재시작 · GC 종류 합산은 조회가 끝내서 준다.
// 행이 없는 1분 = GC 값이 안 왔거나 재시작으로 뺄 수 없던 1분 — 0 으로 채우지 않는다
data class GcTimeBucket(
    val serviceName: String,
    val agentKey: String,
    val start: Instant,
    val gcTimeMs: BigDecimal,
    val gcCount: Long,
)

// 조회 계약이 바뀌면 이 인터페이스 구현만 고친다. 판정 쪽은 그대로
fun interface GcTimeClient {
    // [from, to) 의 모든 서비스 · 모든 파드 (service_name 없이 한 번). 실패하면 예외 (빈 목록으로 삼키지 않는다)
    fun fetch(from: Instant, to: Instant): List<GcTimeBucket>
}

// X-Internal-Token 으로 인증한다. 스스로 재시도하지 않는다 — 다음 주기가 곧 재시도다
class HttpGcTimeClient(
    private val props: EvaluationProperties.Query,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : GcTimeClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(from: Instant, to: Instant): List<GcTimeBucket> {
        val request = HttpRequest.newBuilder(URI.create("${props.baseUrl.trimEnd('/')}$PATH?from=$from&to=$to"))
            .timeout(props.requestTimeout)
            .header("X-Internal-Token", internalToken)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw GcTimeQueryException("gc-time 호출 실패: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) {
            throw GcTimeQueryException("gc-time 응답 ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body())["data"].map {
            GcTimeBucket(
                serviceName = it["service_name"].asText(),
                agentKey = it["agent_key"].asText(),
                start = Instant.parse(it["ts_min"].asText()),
                gcTimeMs = it["gc_time_ms"].decimalValue(),
                gcCount = it["gc_count"].asLong(),
            )
        }
    }

    private companion object {
        const val PATH = "/api/v1/internal/metrics/gc-time"
    }
}

class GcTimeQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

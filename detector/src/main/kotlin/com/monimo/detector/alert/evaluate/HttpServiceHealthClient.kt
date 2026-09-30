package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

// API 서버 내부 문 호출. X-Internal-Token 으로 인증한다 (README · MONIMO_INTERNAL_TOKEN).
// 스스로 재시도하지 않는다 — 다음 평가 주기가 곧 재시도이고, 실패한 주기는 판정 불가로 남긴다
class HttpServiceHealthClient(
    private val props: EvaluationProperties.Query,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : ServiceHealthClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(serviceName: String, from: Instant, to: Instant): List<HealthBucket> {
        val query = "service_name=${encode(serviceName)}&from=$from&to=$to&step=$STEP_SEC"
        val request = HttpRequest.newBuilder(URI.create("${props.baseUrl.trimEnd('/')}$PATH?$query"))
            .timeout(props.requestTimeout)
            .header("X-Internal-Token", internalToken)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw ServiceHealthQueryException("service-health 호출 실패: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) {
            throw ServiceHealthQueryException("service-health 응답 ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body())["data"].map(::toBucket).sortedBy { it.start }
    }

    private fun toBucket(n: JsonNode) = HealthBucket(
        start = Instant.parse(n["ts_min"].asText()),
        cnt = n["cnt"].asLong(),
        cnt4xx = n["cnt_4xx"].asLong(),
        cnt5xx = n["cnt_5xx"].asLong(),
        p95Ms = n["p95_ms"].asLong(),
    )

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val PATH = "/api/v1/internal/service-health"
        const val STEP_SEC = 60  // 1분 버킷. 창(window_sec) 합산은 탐지가 직접 한다
    }
}

package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant

// gc-time 응답 모양(봉투 · snake_case · 파드 × 1분 한 줄)을 흉내 내는 가짜 API 서버. 계약은 조회 #197 PR 본문의 응답 그대로
class HttpGcTimeClientTest : BehaviorSpec({

    var status = 200
    var lastToken: String? = null
    var lastQuery: String? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/internal/metrics/gc-time") { ex ->
            lastToken = ex.requestHeaders.getFirst("X-Internal-Token")
            lastQuery = ex.requestURI.rawQuery
            val body = """
                {"data":[
                  {"service_name":"shop-order","agent_key":"shop-order-7c9d5f-2xk8p","ts_min":"2026-10-14T07:18:00Z","gc_time_ms":400.0,"gc_count":10},
                  {"service_name":"shop-order","agent_key":"shop-order-7c9d5f-2xk8p","ts_min":"2026-10-14T07:19:00Z","gc_time_ms":50.5,"gc_count":1}
                ]}
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    afterSpec { server.stop(0) }

    val client = HttpGcTimeClient(
        EvaluationProperties.Query(baseUrl = "http://127.0.0.1:${server.address.port}"), "secret-token", jacksonObjectMapper(),
    )
    val from = Instant.parse("2026-10-14T07:18:00Z")
    val to = Instant.parse("2026-10-14T07:20:00Z")

    Given("gc-time 조회") {
        When("200 이 오면") {
            status = 200
            val rows = client.fetch(from, to)

            Then("내부 토큰 · 범위를 싣고, service_name 없이 전 서비스를 한 번에 부른다") {
                lastToken shouldBe "secret-token"
                lastQuery!! shouldContain "from=2026-10-14T07:18:00Z"
                lastQuery!! shouldContain "to=2026-10-14T07:20:00Z"
                lastQuery!! shouldNotContain "service_name"
            }

            Then("파드 · 1분 · GC 시간(ms) · 횟수를 그대로 옮긴다") {
                rows shouldBe listOf(
                    GcTimeBucket("shop-order", "shop-order-7c9d5f-2xk8p", Instant.parse("2026-10-14T07:18:00Z"), BigDecimal("400.0"), 10),
                    GcTimeBucket("shop-order", "shop-order-7c9d5f-2xk8p", Instant.parse("2026-10-14T07:19:00Z"), BigDecimal("50.5"), 1),
                )
            }
        }

        When("500 이 오면") {
            status = 500

            Then("빈 목록으로 삼키지 않고 예외를 던진다 (빈 목록이면 모든 1분이 '데이터 없음'이 된다)") {
                shouldThrow<GcTimeQueryException> { client.fetch(from, to) }
            }
        }
    }
})

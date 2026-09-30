package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.time.Instant

// 조회 파트 #48 의 실제 응답 모양(봉투 · snake_case · cnt_4xx)을 흉내 내는 가짜 API 서버
class HttpServiceHealthClientTest : BehaviorSpec({

    var status = 200
    var lastToken: String? = null
    var lastQuery: String? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/internal/service-health") { ex ->
            lastToken = ex.requestHeaders.getFirst("X-Internal-Token")
            lastQuery = ex.requestURI.rawQuery
            val body = """
                {"data":[
                  {"ts_min":"2026-09-30T10:01:00Z","service_name":"order","cnt":90,"err_cnt":9,"cnt_4xx":1,"cnt_5xx":8,"p50_ms":20,"p95_ms":150,"p99_ms":300},
                  {"ts_min":"2026-09-30T10:00:00Z","service_name":"order","cnt":10,"err_cnt":0,"cnt_4xx":0,"cnt_5xx":0,"p50_ms":10,"p95_ms":40,"p99_ms":50}
                ]}
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    afterSpec { server.stop(0) }

    val client = HttpServiceHealthClient(
        EvaluationProperties.Query(baseUrl = "http://127.0.0.1:${server.address.port}"), "secret-token", jacksonObjectMapper(),
    )
    val from = Instant.parse("2026-09-30T10:00:00Z")
    val to = Instant.parse("2026-09-30T10:02:00Z")

    Given("service-health 조회") {
        When("200 이 오면") {
            status = 200
            val rows = client.fetch("order service", from, to)

            Then("내부 토큰 · step=60 · 서비스 이름을 실어 보낸다") {
                lastToken shouldBe "secret-token"
                lastQuery!! shouldContain "step=60"
                lastQuery!! shouldContain "service_name=order+service"
            }

            Then("버킷을 오래된 순으로 읽는다 (cnt_4xx · cnt_5xx · p95_ms)") {
                rows.map { it.start } shouldBe listOf(from, from.plusSeconds(60))
                rows[1].cnt5xx shouldBe 8
                rows[1].cnt4xx shouldBe 1
                rows[1].p95Ms shouldBe 150
            }
        }

        When("500 이 오면") {
            status = 500

            Then("빈 목록으로 삼키지 않고 예외를 던진다 (판정 불가로 남기려고)") {
                shouldThrow<ServiceHealthQueryException> { client.fetch("order", from, to) }
            }
        }
    }
})

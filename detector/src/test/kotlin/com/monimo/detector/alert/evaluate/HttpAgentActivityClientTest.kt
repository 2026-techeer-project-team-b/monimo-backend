package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.time.Instant

// agents/active 응답 모양(봉투 · snake_case · 파드당 한 줄)을 흉내 내는 가짜 API 서버. 계약은 현영 답변(2026-10-04)
class HttpAgentActivityClientTest : BehaviorSpec({

    var status = 200
    var lastToken: String? = null
    var lastQuery: String? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/internal/agents/active") { ex ->
            lastToken = ex.requestHeaders.getFirst("X-Internal-Token")
            lastQuery = ex.requestURI.rawQuery
            val body = """
                {"data":[
                  {"service_name":"shop-order","agent_key":"8fed4107-7cce-4e18-b19a-0b30fb073ed7","last_signal_at":"2026-10-05T10:02:58Z","source":"metrics_raw"},
                  {"service_name":"shop-pay","agent_key":"17332bc8-fcc4-4cd3-8eba-6fc0aff19282","last_signal_at":"2026-10-05T10:02:57Z","source":"spans"}
                ]}
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    afterSpec { server.stop(0) }

    val client = HttpAgentActivityClient(
        EvaluationProperties.Query(baseUrl = "http://127.0.0.1:${server.address.port}"), "secret-token", jacksonObjectMapper(),
    )
    val from = Instant.parse("2026-10-05T10:00:00Z")
    val to = Instant.parse("2026-10-05T10:03:00Z")

    Given("agents/active 조회") {
        When("200 이 오면") {
            status = 200
            val rows = client.fetchActive(from, to)

            Then("내부 토큰 · 범위를 싣고, service_name 없이 전체를 부른다") {
                lastToken shouldBe "secret-token"
                lastQuery!! shouldContain "from=2026-10-05T10:00:00Z"
                lastQuery!! shouldNotContain "service_name"
            }

            Then("서비스 · 키 · 마지막 수신 시각을 그대로 옮긴다") {
                rows shouldBe listOf(
                    AgentSignal("shop-order", "8fed4107-7cce-4e18-b19a-0b30fb073ed7", Instant.parse("2026-10-05T10:02:58Z")),
                    AgentSignal("shop-pay", "17332bc8-fcc4-4cd3-8eba-6fc0aff19282", Instant.parse("2026-10-05T10:02:57Z")),
                )
            }
        }

        When("500 이 오면") {
            status = 500

            Then("빈 목록으로 삼키지 않고 예외를 던진다 (빈 목록이면 '전부 죽음'으로 오판한다)") {
                shouldThrow<AgentActivityQueryException> { client.fetchActive(from, to) }
            }
        }
    }
})

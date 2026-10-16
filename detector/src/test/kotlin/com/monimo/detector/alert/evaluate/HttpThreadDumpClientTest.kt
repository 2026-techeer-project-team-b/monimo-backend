package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.util.UUID

// 자동 스레드 덤프 문(#164) 응답 모양을 흉내 내는 가짜 API 서버. 쿨다운 안이면 200 + skipped (에러가 아니다)
class HttpThreadDumpClientTest : BehaviorSpec({

    var status = 200
    var response = ""
    var lastToken: String? = null
    var lastBody: String? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/internal/thread-dumps") { ex ->
            lastToken = ex.requestHeaders.getFirst("X-Internal-Token")
            lastBody = ex.requestBody.readAllBytes().decodeToString()
            val body = response.toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    afterSpec { server.stop(0) }

    val client = HttpThreadDumpClient(
        "http://127.0.0.1:${server.address.port}", EvaluationProperties.ThreadDump(), "secret-token", jacksonObjectMapper(),
    )
    val agentUuid = UUID.fromString("4a1c2d3e-0000-4000-8000-000000000001")

    Given("자동 스레드 덤프 요청") {
        When("새로 떴으면") {
            status = 200
            response = """{"data":{"skipped":false,"agent_key":"shop-order-local-1","service_name":"shop-order",""" +
                """"dump_uuid":"9b8e0000-0000-4000-8000-000000000002","thread_count":41,"last_dump_at":null}}"""
            val result = client.requestAuto(agentUuid)

            Then("내부 토큰과 snake_case 본문(agent_uuid · requested_by=detector)을 싣는다") {
                lastToken shouldBe "secret-token"
                lastBody!! shouldContain "\"agent_uuid\":\"$agentUuid\""
                lastBody!! shouldContain "\"requested_by\":\"detector\""
            }

            Then("덤프 식별자를 돌려준다") {
                result shouldBe AutoDumpResult(skipped = false, dumpUuid = "9b8e0000-0000-4000-8000-000000000002")
            }
        }

        When("쿨다운 안이면 (200 + skipped)") {
            status = 200
            response = """{"data":{"skipped":true,"agent_key":"shop-order-local-1","service_name":"shop-order",""" +
                """"dump_uuid":null,"thread_count":null,"last_dump_at":"2026-10-16T10:01:00Z"}}"""

            Then("실패가 아니라 skipped 로 돌려준다") {
                client.requestAuto(agentUuid) shouldBe AutoDumpResult(skipped = true, dumpUuid = null)
            }
        }

        When("에이전트에 닿지 못하면 (503 AGENT_NOT_REACHABLE)") {
            status = 503
            response = """{"error":{"code":"AGENT_NOT_REACHABLE"}}"""

            Then("예외를 던진다 — 삼키는 것은 부르는 쪽(GcTimeRunner) 몫이다") {
                shouldThrow<ThreadDumpRequestException> { client.requestAuto(agentUuid) }
            }
        }
    }
})

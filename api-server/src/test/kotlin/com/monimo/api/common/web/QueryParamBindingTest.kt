package com.monimo.api.common.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 실제 요청의 쿼리 문자열이 Instant 로 바뀌고, 검사 결과가 명세 상태 코드로 나가는지 확인한다
@WebMvcTest
@ActiveProfiles("query-param-test")
class QueryParamBindingTest(mockMvc: MockMvc, objectMapper: ObjectMapper) : BehaviorSpec({

    fun call(query: String): MvcResult = mockMvc.get("/test/query-params?$query").andReturn()
    fun body(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    Given("from · to 를 쿼리로 받는 API") {
        When("ISO 8601 UTC 로 보내면") {
            val result = call("from=2026-09-14T10:00:00Z&to=2026-09-14T11:00:00Z")

            Then("200 이고 초 단위 길이가 맞다") {
                result.response.status shouldBe 200
                body(result)["data"]["seconds"].asLong() shouldBe 3600
            }
        }

        When("날짜 모양이 틀리면") {
            val result = call("from=yesterday&to=2026-09-14T11:00:00Z")

            Then("400 INVALID_REQUEST") {
                result.response.status shouldBe 400
                body(result)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("from 이 to 보다 늦으면") {
            val result = call("from=2026-09-14T11:00:00Z&to=2026-09-14T10:00:00Z")

            Then("422 UNPROCESSABLE") {
                result.response.status shouldBe 422
                body(result)["error"]["code"].asText() shouldBe "UNPROCESSABLE"
            }
        }

        When("7일을 넘으면") {
            val result = call("from=2026-09-01T00:00:00Z&to=2026-09-14T00:00:00Z")

            Then("422 TIME_RANGE_TOO_WIDE") {
                result.response.status shouldBe 422
                body(result)["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
            }
        }
    }
})

// 이 테스트에서만 켜지는 가짜 컨트롤러
@Profile("query-param-test")
@RestController
class QueryParamTestController {

    data class Result(val seconds: Long)

    @GetMapping("/test/query-params")
    fun range(@RequestParam from: Instant, @RequestParam to: Instant): ApiResponse<Result> {
        val range = TimeRange.of(from, to)
        return ApiResponse.of(Result(range.duration.seconds))
    }
}

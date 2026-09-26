package com.monimo.api.common.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@WebMvcTest
@ActiveProfiles("envelope-test")
class ApiEnvelopeTest(mockMvc: MockMvc, objectMapper: ObjectMapper) : BehaviorSpec({

    fun call(path: String): MvcResult = mockMvc.get(path).andReturn()
    fun body(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    Given("공통 응답 틀") {
        When("단건을 돌려주면") {
            val result = call("/test/envelope/single")
            val json = body(result)

            Then("200 에 data 만 있고 필드 이름은 snake_case 다") {
                result.response.status shouldBe 200
                json["data"]["trace_id"].asText() shouldBe "abc"
                json.has("page") shouldBe false
                json.has("error") shouldBe false
            }

            Then("X-Request-Id 헤더에 UUID 가 붙는다") {
                result.response.getHeader(RequestIdFilter.HEADER)!! shouldMatch UUID_PATTERN
            }
        }

        When("목록의 마지막 쪽을 돌려주면") {
            val json = body(call("/test/envelope/last-page"))

            Then("page.next_cursor 가 빠지지 않고 null 로 나간다") {
                json["data"].size() shouldBe 2
                json["page"].has("next_cursor") shouldBe true
                json["page"]["next_cursor"].isNull shouldBe true
                json["page"]["limit"].asInt() shouldBe 50
            }
        }

        When("ApiException 을 던지면") {
            val result = call("/test/envelope/too-wide")
            val json = body(result)

            Then("그 코드의 상태와 에러 봉투로 응답하고 data 는 없다") {
                result.response.status shouldBe 422
                json["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
                json["error"]["message"].asText() shouldBe "최대 7일까지 조회할 수 있습니다."
                json.has("data") shouldBe false
            }

            Then("실패 응답에도 X-Request-Id 가 붙는다") {
                result.response.getHeader(RequestIdFilter.HEADER)!! shouldMatch UUID_PATTERN
            }
        }

        When("필수 파라미터가 빠지면") {
            val result = call("/test/envelope/required")

            Then("400 INVALID_REQUEST 와 빠진 파라미터 이름을 알려 준다") {
                result.response.status shouldBe 400
                body(result)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
                body(result)["error"]["message"].asText() shouldBe "필수 파라미터 'from' 이(가) 없습니다."
            }
        }

        When("숫자 파라미터에 글자를 넣으면") {
            val result = call("/test/envelope/required?from=x&limit=abc")

            Then("400 INVALID_REQUEST") {
                result.response.status shouldBe 400
                body(result)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("저장소 연결이 실패하면") {
            val result = call("/test/envelope/upstream-down")

            Then("503 UPSTREAM_UNAVAILABLE") {
                result.response.status shouldBe 503
                body(result)["error"]["code"].asText() shouldBe "UPSTREAM_UNAVAILABLE"
            }
        }

        When("예상 못한 예외가 나면") {
            val result = call("/test/envelope/boom")

            Then("500 INTERNAL_ERROR 로 응답하고 내부 메시지는 숨긴다") {
                result.response.status shouldBe 500
                body(result)["error"]["code"].asText() shouldBe "INTERNAL_ERROR"
                body(result)["error"]["message"].asText() shouldBe ErrorCode.INTERNAL_ERROR.defaultMessage
            }
        }

        When("없는 주소를 부르면") {
            val result = call("/api/v1/no-such-path")

            Then("404 NOT_FOUND 에러 봉투") {
                result.response.status shouldBe 404
                body(result)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }

        When("두 번 부르면") {
            val first = call("/test/envelope/single").response.getHeader(RequestIdFilter.HEADER)
            val second = call("/test/envelope/single").response.getHeader(RequestIdFilter.HEADER)

            Then("X-Request-Id 는 요청마다 다르다") {
                first shouldNotBe second
            }
        }
    }
}) {
    companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

// 이 테스트에서만 켜지는 가짜 컨트롤러
@Profile("envelope-test")
@RestController
@RequestMapping("/test/envelope")
class EnvelopeTestController {

    data class Sample(val traceId: String)

    @GetMapping("/single")
    fun single() = ApiResponse.of(Sample("abc"))

    @GetMapping("/last-page")
    fun lastPage() = ApiResponse.page(listOf(Sample("a"), Sample("b")), nextCursor = null, limit = 50)

    @GetMapping("/too-wide")
    fun tooWide(): ApiResponse<Unit> = throw ApiException(ErrorCode.TIME_RANGE_TOO_WIDE, "최대 7일까지 조회할 수 있습니다.")

    @GetMapping("/required")
    fun required(@RequestParam from: String, @RequestParam(required = false) limit: Int?) = ApiResponse.of(from)

    @GetMapping("/upstream-down")
    fun upstreamDown(): ApiResponse<Unit> = throw DataAccessResourceFailureException("clickhouse down")

    @GetMapping("/boom")
    fun boom(): ApiResponse<Unit> = throw IllegalStateException("내부 비밀 정보")
}

package com.monimo.api.common.web

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CursorCodecTest : BehaviorSpec({

    Given("커서를 만들고 풀 때") {
        When("위치를 커서로 만들었다가 다시 풀면") {
            val cursor = CursorCodec.encode(SamplePosition(1758192030, "a1b2c3d4e5f60718"))

            Then("같은 위치가 나온다") {
                CursorCodec.decode<SamplePosition>(cursor) shouldBe SamplePosition(1758192030, "a1b2c3d4e5f60718")
            }

            Then("주소에 그대로 넣을 수 있는 글자만 쓴다") {
                cursor shouldNotContain "+"
                cursor shouldNotContain "/"
                cursor shouldNotContain "="
            }
        }

        When("Base64 가 아닌 값이 오면") {
            Then("400 INVALID_REQUEST") {
                shouldThrow<ApiException> { CursorCodec.decode<SamplePosition>("!!!") }.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        When("Base64 이지만 모양이 다른 JSON 이 오면") {
            val wrongShape = CursorCodec.encode(mapOf("other" to 1))

            Then("400 INVALID_REQUEST") {
                shouldThrow<ApiException> { CursorCodec.decode<SamplePosition>(wrongShape) }.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
    }

    Given("limit + 1 줄을 읽어 쪽을 만들 때") {
        When("줄이 limit 보다 많으면") {
            val page = CursorCodec.page(listOf(1, 2, 3), limit = 2) { mapOf("n" to it) }

            Then("limit 개만 담고 마지막 줄 위치로 next_cursor 를 만든다") {
                page.data shouldBe listOf(1, 2)
                page.page!!.limit shouldBe 2
                CursorCodec.decode<Map<String, Int>>(page.page!!.nextCursor!!) shouldBe mapOf("n" to 2)
            }
        }

        When("줄이 limit 이하면") {
            val page = CursorCodec.page(listOf(1, 2), limit = 2) { mapOf("n" to it) }

            Then("마지막 쪽이라 next_cursor 는 null") {
                page.data shouldBe listOf(1, 2)
                page.page!!.nextCursor shouldBe null
            }
        }
    }
})

// Jackson 이 만들 수 있도록 파일 최상위에 둔다 (함수 안 로컬 클래스는 역직렬화가 안 된다)
private data class SamplePosition(val ts: Long, val spanId: String)

package com.monimo.ingester.job.dlq

import com.monimo.common.kafka.RawHeaders
import com.monimo.ingester.inbound.kafka.RawErrorHandlerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders

// 되넣기 판정과 머리말 고르기를 컨테이너 없이 고정한다 (#195).
// 이 둘이 잡의 주장 전부다 : 무엇을 되넣고 · 무엇을 가져가나
class DlqReprocessPlanTest : BehaviorSpec({

    fun headers(vararg pairs: Pair<String, String>) = RecordHeaders().apply {
        pairs.forEach { (k, v) -> add(RecordHeader(k, v.toByteArray())) }
    }

    Given("판정 (포기 3회 기준)") {

        When("처음 들어온 일시 장애면") {
            Then("되넣는다") {
                DlqReprocessPlan.decide("transient", attempt = 1, maxAttempt = 3) shouldBe DlqReprocessPlan.Decision.REPLAY
            }
        }

        When("분류가 없는 레코드면") {
            Then("되넣는다 : 머리말이 없는 것은 손으로 넣은 것이거나 옛 수집기 것이다") {
                DlqReprocessPlan.decide(null, attempt = 0, maxAttempt = 3) shouldBe DlqReprocessPlan.Decision.REPLAY
            }
        }

        When("데이터가 틀린 것(독성)이면") {
            Then("건너뛴다 : 되넣어도 그대로 또 실패한다") {
                DlqReprocessPlan.decide("poison", attempt = 1, maxAttempt = 3) shouldBe DlqReprocessPlan.Decision.SKIP_POISON
            }
        }

        When("이미 3번 왕복했으면") {
            Then("멈춘다 : 회차를 넘으면 영원히 왕복하므로 사람을 부른다") {
                DlqReprocessPlan.decide("transient", attempt = 3, maxAttempt = 3) shouldBe DlqReprocessPlan.Decision.STOP_EXHAUSTED
            }
            Then("독성이어도 횟수가 먼저다 : 멈춤이 건너뜀을 이긴다") {
                DlqReprocessPlan.decide("poison", attempt = 5, maxAttempt = 3) shouldBe DlqReprocessPlan.Decision.STOP_EXHAUSTED
            }
        }

        When("포기 횟수를 5로 올리면") {
            Then("3번째는 아직 되넣는다 (env 로 덮어쓸 수 있다는 뜻)") {
                DlqReprocessPlan.decide("transient", attempt = 3, maxAttempt = 5) shouldBe DlqReprocessPlan.Decision.REPLAY
            }
        }
    }

    Given("머리말 읽기") {

        When("x-dlq-attempt 가 2면") {
            Then("2 로 읽는다") {
                DlqReprocessPlan.attemptOf(headers(RawErrorHandlerConfig.HEADER_ATTEMPT to "2")) shouldBe 2
            }
        }

        When("머리말이 없으면") {
            Then("0 이다") {
                DlqReprocessPlan.attemptOf(RecordHeaders()) shouldBe 0
                DlqReprocessPlan.reasonOf(RecordHeaders()) shouldBe null
            }
        }

        When("숫자가 아닌 글자가 들어 있으면") {
            Then("0 으로 본다 : 읽을 수 없는 값 때문에 되넣기가 멈추지 않게") {
                DlqReprocessPlan.attemptOf(headers(RawErrorHandlerConfig.HEADER_ATTEMPT to "두번")) shouldBe 0
            }
        }
    }

    Given("원래 발행 시각 (되넣은 구간을 찍는 값)") {
        // 스프링은 이 머리말을 8바이트 정수로 넣는다. 글자가 아니다
        fun longHeader(name: String, value: Long) = RecordHeaders().apply {
            add(RecordHeader(name, ByteArray(8) { i -> (value shr (8 * (7 - i)) and 0xFF).toByte() }))
        }

        When("8바이트 정수가 들어 있으면") {
            Then("그 값을 읽는다 (실측으로 받은 1791919934609 = 2026-10-13 19:32:14 UTC)") {
                DlqReprocessPlan.originalTimestamp(longHeader(DlqReprocessPlan.HEADER_ORIGINAL_TS, 1_791_919_934_609L)) shouldBe
                    1_791_919_934_609L
            }
        }

        When("머리말이 없으면") {
            Then("null 이라 부르는 쪽이 레코드 시각으로 돌아간다") {
                DlqReprocessPlan.originalTimestamp(RecordHeaders()) shouldBe null
            }
        }

        When("길이가 8이 아니면") {
            Then("안 읽는다 : 글자로 적힌 값을 숫자로 잘못 읽으면 엉뚱한 분을 가리킨다") {
                DlqReprocessPlan.originalTimestamp(headers(DlqReprocessPlan.HEADER_ORIGINAL_TS to "1791919934609")) shouldBe null
            }
        }
    }

    Given("DLQ 레코드의 머리말 13장") {
        val all = headers(
            RawHeaders.PEER_ADDRESS to "172.19.0.8",
            "kafka_dlt-exception-fqcn" to "org.springframework.kafka.listener.ListenerExecutionFailedException",
            "kafka_dlt-exception-cause-fqcn" to "com.clickhouse.client.api.ServerException",
            "kafka_dlt-exception-message" to "Code: 243. DB::Exception: NOT_ENOUGH_SPACE",
            "kafka_dlt-exception-stacktrace" to "a".repeat(5_144), // 실측 크기
            "kafka_dlt-original-topic" to "raw",
            "kafka_dlt-original-partition" to "0",
            "kafka_dlt-original-offset" to "0",
            "kafka_dlt-original-timestamp" to "1791919934609",
            "kafka_dlt-original-timestamp-type" to "CreateTime",
            "kafka_dlt-original-consumer-group" to "ingester",
            RawErrorHandlerConfig.HEADER_ATTEMPT to "1",
            RawErrorHandlerConfig.HEADER_REASON to "transient",
        )

        When("되넣을 머리말을 고르면") {
            val kept = DlqReprocessPlan.keep(all)

            Then("셋만 남고 순서는 원래 순서다") {
                kept.map { it.key() } shouldContainExactly listOf(
                    RawHeaders.PEER_ADDRESS,
                    RawErrorHandlerConfig.HEADER_ATTEMPT,
                    RawErrorHandlerConfig.HEADER_REASON,
                )
            }

            Then("스택 추적은 버린다 : 13장 5,000바이트대가 65바이트 안쪽이 된다") {
                val before = all.sumOf { it.key().length + it.value().size }
                val after = kept.sumOf { it.key().length + it.value().size }
                // 여기 만든 것은 약 5,700 바이트다. 진짜 레코드는 6,033 이었다 (예외 메시지가 388 바이트였다)
                (before > 5_000) shouldBe true
                (after < 100) shouldBe true // 실측 65 바이트
            }

            Then("횟수가 그대로 실려 간다 : 버리면 매번 1 로 돌아가 영원히 왕복한다") {
                DlqReprocessPlan.attemptOf(RecordHeaders(kept.toTypedArray())) shouldBe 1
            }
        }
    }
})

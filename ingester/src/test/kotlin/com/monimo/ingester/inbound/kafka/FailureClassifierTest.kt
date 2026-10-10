package com.monimo.ingester.inbound.kafka

import com.clickhouse.client.api.ConnectionInitiationException
import com.clickhouse.client.api.ServerException
import com.google.protobuf.InvalidProtocolBufferException
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.kafka.listener.ListenerExecutionFailedException
import java.net.UnknownHostException

// 분류기만 본다. 컨테이너 없이 돈다.
// 핵심은 둘이다 : ① 원인 사슬을 따라 내려가 아는 것을 잡는가 ② 우리 목록이 라이브러리 판단보다 앞서는가
class FailureClassifierTest : BehaviorSpec({

    // ServerException(코드, 메시지, HTTP 상태, queryId). isRetryable 은 생성자에서 코드로 계산된다
    fun server(code: Int) = ServerException(code, "code $code", 500, null)

    Given("적재 실패 예외") {

        When("Kafka 에서 꺼낸 바이트가 protobuf 가 아니라 InvalidProtocolBufferException 이 나면") {
            val failure = InvalidProtocolBufferException("While parsing a protocol message, the input ended unexpectedly")
            Then("독성이다 : 다시 넣어도 똑같이 실패한다") {
                FailureClassifier.classify(failure) shouldBe FailureClass.POISON
            }
        }

        When("키가 traces · metrics · logs 어느 것도 아니라 RawSignal.fromKey 가 IllegalArgumentException 을 던지면") {
            val failure = runCatching { com.monimo.common.kafka.RawSignal.fromKey("??") }.exceptionOrNull()!!
            Then("독성이다 : 메시지 자체가 약속을 어겼다. 재시도 1분 쓰고 DLQ 로 가는 것보다 바로 가는 게 맞다") {
                FailureClassifier.classify(failure) shouldBe FailureClass.POISON
            }
        }

        When("ClickHouse 에 닿지 못해 ConnectionInitiationException 이 나면") {
            val failure = ConnectionInitiationException("Insert request failed", UnknownHostException("clickhouse"))
            Then("일시 장애다 : 기다리면 된다") {
                FailureClassifier.classify(failure) shouldBe FailureClass.TRANSIENT
            }
        }

        When("그 예외가 스프링의 ListenerExecutionFailedException 에 한 겹 싸여 오면") {
            // 실제로 우리가 받는 모양이다 : ListenerExecutionFailedException → ConnectionInitiationException → UnknownHostException
            val failure = ListenerExecutionFailedException(
                "Listener method threw exception",
                ConnectionInitiationException("Insert request failed", UnknownHostException("clickhouse")),
            )
            Then("원인 사슬을 따라 내려가 일시 장애로 본다") {
                FailureClassifier.classify(failure) shouldBe FailureClass.TRANSIENT
            }
        }
    }

    Given("ClickHouse 서버가 거절한 ServerException") {

        When("코드가 117 INCORRECT_DATA 처럼 데이터가 틀렸다는 뜻이면") {
            Then("독성이다") {
                listOf(117, 27, 53, 41, 72).forEach { code ->
                    FailureClassifier.classify(server(code)) shouldBe FailureClass.POISON
                }
            }
        }

        When("코드가 319 UNKNOWN_STATUS_OF_INSERT 이면") {
            val failure = server(319)
            Then("라이브러리는 재시도해도 된다고 하지만") {
                failure.isRetryable shouldBe true // 라이브러리 화이트리스트에 들어 있다
            }
            Then("우리는 독성으로 본다 : 재시도하면 중복 적재가 된다 (ADR #51 채택 ③)") {
                FailureClassifier.classify(failure) shouldBe FailureClass.POISON
            }
        }

        When("코드가 243 NOT_ENOUGH_SPACE (디스크 꽉 참) 이면") {
            val failure = server(243)
            Then("라이브러리는 재시도 대상이 아니라고 하지만") {
                failure.isRetryable shouldBe false // 화이트리스트에 없다
            }
            Then("우리는 일시 장애로 올린다 : 디스크를 늘려 주면 낫는다 (ADR #51 채택 ④)") {
                FailureClassifier.classify(failure) shouldBe FailureClass.TRANSIENT
            }
        }

        When("코드가 252 TOO_MANY_PARTS 처럼 라이브러리 화이트리스트에 있는 것이면") {
            Then("라이브러리 판단대로 일시 장애다") {
                FailureClassifier.classify(server(252)) shouldBe FailureClass.TRANSIENT
            }
        }

        When("우리 목록에도 라이브러리 목록에도 없는 코드면") {
            val failure = server(999_999)
            Then("모르는 실패다 : DLQ 가 아니라 재시도 쪽으로 (분류 뒤집기)") {
                failure.isRetryable shouldBe false
                FailureClassifier.classify(failure) shouldBe FailureClass.UNKNOWN
            }
        }
    }

    Given("아는 것이 하나도 없는 예외") {
        When("그냥 RuntimeException 이면") {
            Then("모르는 실패다 : 안전한 쪽으로") {
                FailureClassifier.classify(RuntimeException("?")) shouldBe FailureClass.UNKNOWN
            }
        }
    }
})

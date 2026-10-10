package com.monimo.ingester.inbound.kafka

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import java.time.Duration

// 분류 → 대기 규칙 대응을 컨테이너 없이 고정한다. 이게 이 PR 의 핵심 주장(세 단) 인데 컨테이너 테스트는 독성(0회)만 본다.
class BackOffMappingTest : BehaviorSpec({

    val props = RetryProperties() // 기본값 : 2초 · 2.0배 · 최대 30초 · 일시 장애 10분 · 모르는 실패 1분

    Given("RetryProperties 기본값") {

        When("독성이면") {
            val execution = RawErrorHandlerConfig.backOffFor(FailureClass.POISON, props).start()
            Then("첫 실패에 바로 STOP 이라 재시도가 없다") {
                execution.nextBackOff() shouldBe BackOffExecution.STOP
            }
        }

        When("확실한 일시 장애면") {
            val backOff = RawErrorHandlerConfig.backOffFor(FailureClass.TRANSIENT, props)
            Then("지수 백오프이고 총 10분까지다") {
                backOff.shouldBeInstanceOf<ExponentialBackOff>()
                backOff.initialInterval shouldBe 2_000L
                backOff.multiplier shouldBe 2.0
                backOff.maxInterval shouldBe 30_000L
                backOff.maxElapsedTime shouldBe Duration.ofMinutes(10).toMillis()
            }
            Then("대기가 2초 → 4초 → 8초 → 16초 → 30초(상한) 로 늘어난다") {
                val execution = backOff.start()
                listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L).forEach { expected ->
                    execution.nextBackOff() shouldBe expected
                }
            }
            Then("부를 때마다 새 인스턴스라 레코드끼리 상태가 섞이지 않는다") {
                RawErrorHandlerConfig.backOffFor(FailureClass.TRANSIENT, props) shouldNotBeSameInstanceAs backOff
            }
        }

        When("모르는 실패면") {
            val backOff = RawErrorHandlerConfig.backOffFor(FailureClass.UNKNOWN, props)
            Then("같은 모양에 총 1분까지만이다") {
                backOff.shouldBeInstanceOf<ExponentialBackOff>()
                backOff.maxElapsedTime shouldBe Duration.ofMinutes(1).toMillis()
            }
            Then("간격의 합이 1분을 넘기면 STOP 이다 : 2 + 4 + 8 + 16 + 30 = 60초, 그 다음은 멈춘다") {
                val execution = backOff.start()
                repeat(5) { execution.nextBackOff() } // 2 · 4 · 8 · 16 · 30 = 60초
                execution.nextBackOff() shouldBe BackOffExecution.STOP
            }
        }
    }

    Given("대기 시간을 바꾼 RetryProperties") {
        val short = RetryProperties(transientMaxElapsed = Duration.ofSeconds(5), unknownMaxElapsed = Duration.ofSeconds(1))
        When("일시 장애 대기를 5초로 주면") {
            Then("그 값이 그대로 BackOff 에 들어간다 (env 로 덮어쓸 수 있다는 뜻)") {
                (RawErrorHandlerConfig.backOffFor(FailureClass.TRANSIENT, short) as ExponentialBackOff).maxElapsedTime shouldBe 5_000L
            }
        }
    }
})

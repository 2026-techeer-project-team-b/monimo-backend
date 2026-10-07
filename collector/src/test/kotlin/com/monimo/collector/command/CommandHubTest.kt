package com.monimo.collector.command

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// 명령 보관소의 판단만 본다. 스프링도 컨테이너도 띄우지 않는다.
// DeferredResult 는 서블릿 없이도 setResult 한 값을 result 로 돌려줘서 그걸 읽는다
class CommandHubTest : BehaviorSpec({
    class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    val order = AgentKey("shop-order", "shop-order-local-1")
    fun hub(): Pair<CommandHub, MovableClock> {
        val clock = MovableClock(Instant.parse("2026-10-08T00:00:00Z"))
        return CommandHub(AgentCommandProperties(advertisedUrl = "http://collector-0:8081")).apply { this.clock = clock } to clock
    }
    fun status(r: Any?) = (r as ResponseEntity<*>).statusCode

    Given("에이전트가 폴링 중일 때") {
        When("덤프 명령을 내리면") {
            val (hub, _) = hub()
            val polling = hub.poll(order)
            val result = hub.dispatch(order, Duration.ofSeconds(10))

            Then("붙잡은 폴링이 200 + THREAD_DUMP 명령으로 끝나고, reply_to 는 이 수집기 주소다") {
                result.shouldNotBeNull()
                val command = (polling.result as ResponseEntity<*>).body as AgentCommand
                command.type shouldBe "THREAD_DUMP"
                command.replyTo shouldBe "http://collector-0:8081"
                command.timeoutMs shouldBe 10_000
            }
            Then("Extension 이 그 command_id 로 결과를 보내면 기다리던 쪽이 200 + 본문을 받는다") {
                val command = (polling.result as ResponseEntity<*>).body as AgentCommand
                hub.complete(command.commandId, """{"dump":"x"}""") shouldBe true
                status(result!!.result) shouldBe HttpStatus.OK
                (result.result as ResponseEntity<*>).body shouldBe """{"dump":"x"}"""
            }
        }
        When("같은 에이전트가 다시 폴링하면 (Extension 재시작 · 재시도)") {
            val (hub, _) = hub()
            val first = hub.poll(order)
            val second = hub.poll(order)
            Then("앞의 폴링은 409 로 끝나고(Extension 이 백오프) 보유는 하나다") {
                status(first.result) shouldBe HttpStatus.CONFLICT
                second.hasResult() shouldBe false
                hub.holdingCount() shouldBe 1
            }
        }
    }

    Given("이 수집기가 그 에이전트를 쥐고 있지 않을 때") {
        When("한 번도 폴링이 없었으면") {
            val (hub, _) = hub()
            Then("null 이라 팬아웃 받은 쪽이 503 을 낸다") {
                hub.dispatch(order, Duration.ofSeconds(10)).shouldBeNull()
            }
        }
        When("폴링이 방금(2초 안) 끝나 다음 폴링을 기다리는 빈틈이면") {
            val (hub, clock) = hub()
            val polling = hub.poll(order)
            polling.setResult(CommandHub.NO_COMMAND)
            hub.ended(order, polling)
            clock.now = clock.now.plusSeconds(1)
            val result = hub.dispatch(order, Duration.ofSeconds(10))
            Then("명령을 보관했다가 다음 폴링에 바로 준다") {
                result.shouldNotBeNull()
                val next = hub.poll(order)
                ((next.result as ResponseEntity<*>).body as AgentCommand).type shouldBe "THREAD_DUMP"
            }
        }
        When("폴링이 끝난 지 2초가 넘었으면") {
            val (hub, clock) = hub()
            val polling = hub.poll(order)
            polling.setResult(CommandHub.NO_COMMAND)
            hub.ended(order, polling)
            clock.now = clock.now.plusSeconds(3)
            Then("보유가 아니다 (503)") {
                hub.dispatch(order, Duration.ofSeconds(10)).shouldBeNull()
            }
        }
        When("붙잡은 폴링이 막 타임아웃으로 끝났는데 아직 정리 전이면 (setResult 가 실패하는 몇 ms)") {
            val (hub, _) = hub()
            val polling = hub.poll(order)
            polling.setResult(CommandHub.NO_COMMAND) // 타임아웃이 먼저 결과를 넣었다. onCompletion(ended) 은 아직
            val result = hub.dispatch(order, Duration.ofSeconds(10))
            Then("503 이 아니라 빈틈으로 보고 다음 폴링에 준다 (리뷰가 잡은 경합)") {
                result.shouldNotBeNull()
                ((hub.poll(order).result as ResponseEntity<*>).body as AgentCommand).type shouldBe "THREAD_DUMP"
            }
        }
        When("빈틈에 명령이 두 개 오면") {
            val (hub, _) = hub()
            val polling = hub.poll(order)
            polling.setResult(CommandHub.NO_COMMAND)
            hub.ended(order, polling)
            val first = hub.dispatch(order, Duration.ofSeconds(10))
            val second = hub.dispatch(order, Duration.ofSeconds(10))
            Then("앞 명령을 덮어쓰지 않는다 (뒤 명령은 503 으로 API 서버가 다시 시도)") {
                first.shouldNotBeNull()
                second.shouldBeNull()
            }
        }
        When("다른 에이전트만 폴링 중이면") {
            val (hub, _) = hub()
            hub.poll(AgentKey("shop-payment", "shop-payment-local-1"))
            Then("주문 에이전트는 보유가 아니다") {
                hub.dispatch(order, Duration.ofSeconds(10)).shouldBeNull()
            }
        }
    }

    Given("결과 받기") {
        When("모르는 command_id 면 (시간 초과로 이미 끝났거나 다른 수집기 명령)") {
            val (hub, _) = hub()
            Then("false 라 Extension 이 404 를 받는다") {
                hub.complete("unknown", "{}") shouldBe false
            }
        }
    }
})

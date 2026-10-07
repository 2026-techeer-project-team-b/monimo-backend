package com.monimo.collector.inbound.agent

import com.monimo.collector.command.AgentCommandProperties
import com.monimo.collector.command.CommandHub
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.AsyncEvent
import org.springframework.http.MediaType
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

// 문 셋의 토큰 · 응답 코드를 본다. 스프링 컨텍스트 없이 컨트롤러만 세운다 (Kafka · PG 불필요)
class AgentCommandControllerTest : BehaviorSpec({
    fun mvc(props: AgentCommandProperties = AgentCommandProperties(token = "agent", internalToken = "internal")): MockMvc =
        MockMvcBuilders.standaloneSetup(AgentCommandController(CommandHub(props), props)).build()

    fun MockMvc.finish(builder: org.springframework.test.web.servlet.RequestBuilder): Int {
        val started = perform(builder).andReturn()
        return if (started.request.isAsyncStarted) perform(asyncDispatch(started)).andReturn().response.status else started.response.status
    }

    Given("GET /agent/commands") {
        When("토큰이 없거나 틀리면") {
            Then("401") {
                mvc().finish(get("/agent/commands").param("service", "shop-order").param("instance", "i-1")) shouldBe 401
                mvc().finish(get("/agent/commands").param("service", "shop-order").param("instance", "i-1").header("X-Monimo-Agent-Token", "nope")) shouldBe 401
            }
        }
        When("수집기에 토큰이 설정돼 있지 않으면 (문이 닫힘)") {
            Then("맞는 척하는 빈 값도 401") {
                mvc(AgentCommandProperties()).finish(get("/agent/commands").param("service", "s").param("instance", "i").header("X-Monimo-Agent-Token", "")) shouldBe 401
            }
        }
        When("토큰이 맞고 명령이 없는 채 대기 시간이 끝나면") {
            val m = mvc(AgentCommandProperties(token = "agent", internalToken = "internal", pollTimeout = java.time.Duration.ofMillis(50)))
            val started = m.perform(get("/agent/commands").param("service", "shop-order").param("instance", "i-1").header("X-Monimo-Agent-Token", "agent")).andReturn()
            started.request.isAsyncStarted shouldBe true
            // MockMvc 는 타임아웃을 스스로 일으키지 않아 서블릿 컨테이너가 하는 일(onTimeout)을 대신 부른다
            val async = started.request.asyncContext as MockAsyncContext
            async.listeners.forEach { it.onTimeout(AsyncEvent(async)) }
            Then("503 이 아니라 204 (명령 없음)") {
                m.perform(asyncDispatch(started)).andReturn().response.status shouldBe 204
            }
        }
    }

    Given("POST /internal/thread-dump") {
        val body = """{"service":"shop-order","instance":"i-1","timeout_ms":1000}"""
        When("내부 토큰이 틀리면") {
            Then("401 (에이전트 토큰으로는 안 열린다)") {
                mvc().finish(post("/internal/thread-dump").contentType(MediaType.APPLICATION_JSON).content(body).header("X-Internal-Token", "agent")) shouldBe 401
            }
        }
        When("이 수집기가 그 에이전트를 쥐고 있지 않으면") {
            Then("503 AGENT_NOT_REACHABLE") {
                mvc().finish(post("/internal/thread-dump").contentType(MediaType.APPLICATION_JSON).content(body).header("X-Internal-Token", "internal")) shouldBe 503
            }
        }
    }

    Given("POST /agent/commands/{id}/result") {
        When("모르는 명령이면") {
            Then("404") {
                mvc().finish(post("/agent/commands/nope/result").content("{}").header("X-Monimo-Agent-Token", "agent")) shouldBe 404
            }
        }
    }
})

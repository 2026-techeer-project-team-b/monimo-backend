package com.monimo.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

@SpringBootTest
class ApiServerApplicationTest(environment: Environment) : BehaviorSpec({

    Given("API 서버 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 api-server 로 잡힌다") {
                name shouldBe "api-server"
            }
        }
    }
})

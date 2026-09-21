package com.monimo.notifier

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

@SpringBootTest
class NotifierApplicationTest(environment: Environment) : BehaviorSpec({

    Given("알림 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 notifier 로 잡힌다") {
                name shouldBe "notifier"
            }
        }
    }
})

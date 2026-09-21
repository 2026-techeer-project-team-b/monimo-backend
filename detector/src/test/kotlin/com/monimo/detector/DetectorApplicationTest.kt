package com.monimo.detector

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

@SpringBootTest
class DetectorApplicationTest(environment: Environment) : BehaviorSpec({

    Given("탐지 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 detector 로 잡힌다") {
                name shouldBe "detector"
            }
        }
    }
})

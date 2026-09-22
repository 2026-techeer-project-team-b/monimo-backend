package com.monimo.api.common.config

import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class OpenApiDocsDisabledTest(rest: TestRestTemplate) : BehaviorSpec({

    Given("기본 설정의 API 서버 (프로필 없음 = 운영과 같은 기본값)") {
        When("REST 명세 주소를 부르면") {
            val response = rest.getForEntity("/v3/api-docs", String::class.java)

            Then("404 로 닫혀 있다") {
                response.statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }

        When("Swagger UI 주소를 부르면") {
            val response = rest.getForEntity("/swagger-ui/index.html", String::class.java)

            Then("404 로 닫혀 있다") {
                response.statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }
    }
})

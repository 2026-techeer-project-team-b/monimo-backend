package com.monimo.api.common.config

import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"],
)
@Import(TestInfraConfig::class)
class OpenApiDocsEnabledTest(rest: TestRestTemplate) : BehaviorSpec({

    Given("springdoc 을 켠 API 서버 (local 프로필과 같은 설정)") {
        When("REST 명세 주소를 부르면") {
            val response = rest.getForEntity("/v3/api-docs", String::class.java)

            Then("OpenAPI 명세가 200 으로 온다") {
                response.statusCode shouldBe HttpStatus.OK
                response.body!! shouldContain "\"openapi\""
            }

            Then("제목이 모니모니터링 API 서버다") {
                response.body!! shouldContain "모니모니터링 API 서버"
            }
        }

        When("Swagger UI 주소를 부르면") {
            val response = rest.getForEntity("/swagger-ui/index.html", String::class.java)

            Then("화면이 200 으로 온다") {
                response.statusCode shouldBe HttpStatus.OK
            }
        }
    }
})

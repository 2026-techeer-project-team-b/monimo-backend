package com.monimo.notifier

import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

// 헬스체크 규약: liveness · readiness 를 관리 포트로 열고, readiness 는 PostgreSQL(db)까지 본다.
// RANDOM_PORT 면 스프링이 관리 포트도 빈 포트로 바꿔 준다 (운영 8081 과 부딪치지 않는다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["monimo.notifier.delivery.enabled=false"])
@Import(TestInfraConfig::class)
class HealthProbeTest(
    environment: Environment,
    groups: HealthEndpointGroups,
) : BehaviorSpec({

    val management = "http://localhost:${environment.getProperty("local.management.port")}"
    val rest = TestRestTemplate()

    Given("알림 이 떠 있고 PostgreSQL 에 붙어 있다") {
        When("probe 두 개를 부르면") {
            val liveness = rest.getForEntity("$management/actuator/health/liveness", String::class.java)
            val readiness = rest.getForEntity("$management/actuator/health/readiness", String::class.java)

            Then("둘 다 200 이고 본문은 status 하나뿐이다 (어느 저장소를 쓰는지 드러내지 않는다)") {
                liveness.statusCode.value() shouldBe 200
                readiness.statusCode.value() shouldBe 200
                liveness.body shouldBe """{"status":"UP"}"""
                readiness.body shouldBe """{"status":"UP"}"""
            }
        }

        When("probe 그룹 구성을 보면") {
            val readiness = groups.get("readiness")
            val liveness = groups.get("liveness")

            Then("readiness 는 db 를 보고, liveness 는 자기 자신만 본다 (DB 가 죽었다고 재시작하지 않는다)") {
                readiness.isMember("db") shouldBe true
                liveness.isMember("db") shouldBe false
            }
        }

        When("업무 포트로 actuator 를 부르면") {
            val onAppPort = TestRestTemplate().getForEntity(
                "http://localhost:${environment.getProperty("local.server.port")}/actuator/health/readiness", String::class.java,
            )

            Then("없다 — actuator 는 관리 포트에만 있다") {
                onAppPort.statusCode.value() shouldBe 404
            }
        }
    }
})

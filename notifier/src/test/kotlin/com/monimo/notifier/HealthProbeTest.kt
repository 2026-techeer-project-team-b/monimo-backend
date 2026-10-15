package com.monimo.notifier

import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

// 헬스체크 규약 (#211 · ADR #58): liveness · readiness 를 관리 포트와 업무 포트(/livez · /readyz)로 열고, readiness 는 저장소(db)를 안 본다.
// RANDOM_PORT 면 스프링이 관리 포트도 빈 포트로 바꿔 준다 (운영 18084 과 부딪치지 않는다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["monimo.notifier.delivery.enabled=false"])
@Import(TestInfraConfig::class)
class HealthProbeTest(
    environment: Environment,
    groups: HealthEndpointGroups,
) : BehaviorSpec({

    val management = "http://localhost:${environment.getProperty("local.management.port")}"
    val business = "http://localhost:${environment.getProperty("local.server.port")}"
    val rest = TestRestTemplate()

    Given("알림 이 떠 있고 PostgreSQL 에 붙어 있다") {
        When("업무 포트의 /livez · /readyz 를 부르면") {
            val livez = rest.getForEntity("$business/livez", String::class.java)
            val readyz = rest.getForEntity("$business/readyz", String::class.java)

            Then("둘 다 200 이고 본문은 관리 포트 probe 와 같다") {
                livez.statusCode.value() shouldBe 200
                readyz.statusCode.value() shouldBe 200
                livez.body shouldBe """{"status":"UP"}"""
                readyz.body shouldBe """{"status":"UP"}"""
            }
        }

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

            Then("liveness · readiness 어느 그룹에도 db 가 없다 (저장소가 흔들려도 파드가 빠지지 않는다, ADR #58)") {
                readiness.isMember("db") shouldBe false
                liveness.isMember("db") shouldBe false
            }
        }

        When("관리 포트로 낡음 게이지를 부르면 (ADR #54)") {
            val gauge = rest.getForEntity("$management/actuator/metrics/monimo.notifier.delivery.poll.age", String::class.java)

            Then("200 이고 단위는 초") {
                gauge.statusCode.value() shouldBe 200
                (gauge.body!!.contains("\"baseUnit\":\"seconds\"")) shouldBe true
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

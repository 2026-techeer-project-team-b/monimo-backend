package com.monimo.ingester

import com.monimo.ingester.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.testcontainers.containers.PostgreSQLContainer

// 헬스체크 probe (#135 · ADR #58). 수집기의 HealthProbeTest 와 같은 모양이다 :
// 업무 포트에도 /livez · /readyz 를 열고, readiness 가 저장소(db)를 안 본다.
// 적재 처리기는 요청을 받지 않고 Kafka 에서 스스로 꺼내므로 readiness 가 하는 일은 배포 진행을 막는 것뿐이다. 그래도 같은 규칙으로 고정한다.
// RANDOM_PORT 라 업무 · 관리 포트가 둘 다 빈 포트로 바뀐다 (운영 8082 · 18082 와 부딪치지 않는다).
// Hikari 연결 대기를 3초로 줄인 것은 마지막 Given 에서 PG 를 멈춘 뒤 전체 /actuator/health 가 DOWN 으로 답할 때까지 기다리는 시간이다 (운영 기본값 30초)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.hikari.connection-timeout=3000"],
)
@Import(TestInfraConfig::class)
class HealthProbeTest(
    environment: Environment,
    groups: HealthEndpointGroups,
    registry: HealthContributorRegistry,
    postgres: PostgreSQLContainer<*>,
) : BehaviorSpec({

    val business = "http://localhost:${environment.getProperty("local.server.port")}"
    val management = "http://localhost:${environment.getProperty("local.management.port")}"
    val rest = TestRestTemplate()
    fun get(url: String) = rest.getForEntity(url, String::class.java)

    Given("적재 처리기가 떠 있고 PostgreSQL 에 붙어 있다") {
        When("업무 포트의 /livez · /readyz 를 부르면") {
            val livez = get("$business/livez")
            val readyz = get("$business/readyz")

            Then("둘 다 200 이고 본문은 status 하나뿐이다 (어느 저장소를 쓰는지 드러내지 않는다)") {
                livez.statusCode.value() shouldBe 200
                readyz.statusCode.value() shouldBe 200
                livez.body shouldBe """{"status":"UP"}"""
                readyz.body shouldBe """{"status":"UP"}"""
            }
        }

        When("관리 포트의 /actuator/health/liveness · /readiness 를 부르면") {
            val liveness = get("$management/actuator/health/liveness")
            val readiness = get("$management/actuator/health/readiness")

            Then("둘 다 200 이다 (규약의 주소도 그대로 살아 있다)") {
                liveness.statusCode.value() shouldBe 200
                readiness.statusCode.value() shouldBe 200
                liveness.body shouldBe """{"status":"UP"}"""
                readiness.body shouldBe """{"status":"UP"}"""
            }
        }

        When("업무 포트로 /actuator/** 를 부르면") {
            val health = get("$business/actuator/health")
            val metrics = get("$business/actuator/metrics")

            Then("없다 : actuator 는 관리 포트로 옮겨 갔고 업무 포트에 남은 것은 /livez · /readyz 둘뿐이다") {
                health.statusCode.value() shouldBe 404
                metrics.statusCode.value() shouldBe 404
            }
        }

        When("probe 그룹 구성을 보면") {
            val liveness = groups.get("liveness")
            val readiness = groups.get("readiness")

            Then("db 지표 자체는 있다 (PostgreSQL 을 쓰므로 자동 등록된다). 빼는 것은 그룹 쪽이다") {
                registry.getContributor("db").shouldNotBeNull()
            }

            Then("liveness 그룹에 db 가 없다 (DB 가 죽었다고 재시작하지 않는다)") {
                liveness.isMember("db") shouldBe false
            }

            Then("readiness 그룹에도 db 가 없다 (규약과 다른 선택이라 고정한다. 탐지 · 알림은 넣었다)") {
                readiness.isMember("db") shouldBe false
            }
        }
    }

    // 이 Given 은 반드시 마지막이어야 한다. 컨테이너를 실제로 멈추므로 뒤에 Given 을 더하면 죽은 PG 로 돌아 원인이 안 보이는 실패가 난다.
    // 다른 스펙은 안전하다 : 이 스펙의 컨텍스트 키(RANDOM_PORT + 고유 속성)가 어느 스펙과도 안 겹쳐 PG 컨테이너가 따로 뜬다
    Given("PostgreSQL 이 멈췄다") {
        postgres.stop()

        When("전체 /actuator/health 와 probe 둘을 부르면") {
            val whole = get("$management/actuator/health")
            val livez = get("$business/livez")
            val readyz = get("$business/readyz")

            Then("전체는 503 (db DOWN) 인데 probe 둘은 200 그대로다 : 저장소가 죽어도 적재 처리기는 빠지지도 재시작되지도 않는다") {
                whole.statusCode.value() shouldBe 503
                livez.statusCode.value() shouldBe 200
                readyz.statusCode.value() shouldBe 200
            }
        }
    }
})

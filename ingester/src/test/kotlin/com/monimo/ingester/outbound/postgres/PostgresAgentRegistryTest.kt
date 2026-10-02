package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.support.TestInfraConfig
import com.monimo.ingester.transform.AgentSighting
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

// 진짜 PostgreSQL 에 넣고 다시 읽어 확인한다. 표는 TestInfraConfig 가 db/postgres 마이그레이션으로 만들어 둔다.
// 가짜 데이터는 테스트에 들어오지 않으므로 감시 대상 서비스를 여기서 직접 넣는다
@SpringBootTest
@Import(TestInfraConfig::class)
class PostgresAgentRegistryTest(
    registry: PostgresAgentRegistry,
    counter: AgentRegisterCounter,
    jdbc: JdbcTemplate,
) : BehaviorSpec({

    val seenAt = Instant.parse("2026-10-01T03:04:05Z")

    fun sighting(service: String, key: String) = AgentSighting(
        serviceName = service,
        agentKey = key,
        hostname = "$key.pod",
        jvmVersion = "17.0.9",
        agentVersion = "2.11.0",
        seenAt = seenAt,
    )

    fun rows(key: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM agents WHERE agent_key = ?", Int::class.java, key)!!

    // 테스트마다 서비스 이름을 다르게 만들어 서로 간섭하지 않게 한다
    fun newService(prefix: String): String {
        val name = "$prefix-${System.nanoTime()}"
        jdbc.update("INSERT INTO applications (name) VALUES (?)", name)
        return name
    }

    Given("화면에 등록된 감시 대상 서비스") {
        val service = newService("shop-order")

        When("처음 보는 파드를 등록하면") {
            val key = "$service-pod-a"
            registry.register(listOf(sighting(service, key)))

            Then("agents 에 한 줄이 생긴다") {
                rows(key) shouldBe 1
            }

            Then("서비스 번호 · 환경 정보 · 처음 본 시각이 들어가고 상태는 UNKNOWN 이다 (탐지가 바꾼다)") {
                val row = jdbc.queryForMap(
                    """
                    SELECT a.hostname, a.jvm_version, a.agent_version, a.status, a.first_seen_at, app.name AS service
                    FROM agents a JOIN applications app ON app.id = a.application_id
                    WHERE a.agent_key = ?
                    """.trimIndent(),
                    key,
                )
                row["service"] shouldBe service
                row["hostname"] shouldBe "$key.pod"
                row["jvm_version"] shouldBe "17.0.9"
                row["agent_version"] shouldBe "2.11.0"
                row["status"] shouldBe "UNKNOWN"
                (row["first_seen_at"] as java.sql.Timestamp).toInstant() shouldBe seenAt
            }

            Then("ip 는 비어 있다 — 수집기가 채워 줄 코드가 아직 없다") {
                jdbc.queryForObject("SELECT ip FROM agents WHERE agent_key = ?", String::class.java, key) shouldBe null
            }
        }

        When("같은 파드를 다시 등록하면") {
            val key = "$service-pod-b"
            registry.register(listOf(sighting(service, key)))
            registry.register(listOf(sighting(service, key)))

            Then("줄이 늘지 않는다 (ON CONFLICT DO NOTHING)") {
                rows(key) shouldBe 1
            }
        }

        When("파드 3개를 한 번에 등록하면") {
            val keys = listOf("$service-pod-c", "$service-pod-d", "$service-pod-e")
            registry.register(keys.map { sighting(service, it) })

            Then("세 줄이 생긴다") {
                keys.forEach { rows(it) shouldBe 1 }
            }
        }

        When("같은 파드가 한 메시지에 두 번 들어오면") {
            val key = "$service-pod-f"
            registry.register(listOf(sighting(service, key), sighting(service, key)))

            Then("한 줄만 생긴다") {
                rows(key) shouldBe 1
            }
        }
    }

    Given("화면에 등록되지 않은 서비스") {
        When("그 서비스의 파드를 등록하면") {
            val before = counter.count(AgentRegisterCounter.Outcome.UNKNOWN_SERVICE)
            val key = "ghost-pod-${System.nanoTime()}"
            registry.register(listOf(sighting("not-registered-${System.nanoTime()}", key)))

            Then("줄이 생기지 않는다 — applications 에 이름이 없으면 FK 를 채울 수 없다") {
                rows(key) shouldBe 0
            }

            Then("건너뛴 것을 센다") {
                counter.count(AgentRegisterCounter.Outcome.UNKNOWN_SERVICE) shouldBe before + 1
            }
        }
    }

    Given("제외된(deleted_at 이 찍힌) 서비스") {
        val service = newService("retired")
        jdbc.update("UPDATE applications SET deleted_at = now() WHERE name = ?", service)

        When("그 서비스의 파드를 등록하면") {
            val key = "$service-pod"
            registry.register(listOf(sighting(service, key)))

            Then("줄이 생기지 않는다 — 감시를 끊은 서비스에 파드를 새로 달지 않는다") {
                rows(key) shouldBe 0
            }
        }
    }

    Given("파드 식별자나 서비스 이름이 없는 목격") {
        When("등록을 시도하면") {
            registry.register(
                listOf(
                    sighting("some-service", ""), // 파드 식별자 없음
                    sighting("", "orphan-pod"), // 서비스 이름 없음 (unknown_service 포함)
                ),
            )

            Then("아무 줄도 생기지 않는다 — ClickHouse 와 이을 수 없는 줄은 만들지 않는다") {
                rows("") shouldBe 0
                rows("orphan-pod") shouldBe 0
            }
        }
    }
})

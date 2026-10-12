package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.support.TestInfraConfig
import com.monimo.ingester.transform.AgentSighting
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
    repository: AgentRepository, // 주소 고치는 SQL 의 조건을 직접 확인할 때만 쓴다
    counter: AgentRegisterCounter,
    jdbc: JdbcTemplate,
) : BehaviorSpec({

    val seenAt = Instant.parse("2026-10-01T03:04:05Z")
    val laterAt = Instant.parse("2026-10-02T06:07:08Z") // 주소가 바뀐 뒤에 본 시각

    // ip 는 수집기가 봉투 메모로 보내 주는 값이라 기본값을 null 로 둔다. 메모가 없는 메시지가 그 모양이다
    fun sighting(service: String, key: String, ip: String? = null, at: Instant = seenAt) = AgentSighting(
        serviceName = service,
        agentKey = key,
        hostname = "$key.pod",
        ip = ip,
        jvmVersion = "17.0.9",
        agentVersion = "2.11.0",
        seenAt = at,
    )

    // INET 칸은 글자로 읽는다. 조회 API 도 host() 로 글자를 만들어 내보낸다
    fun ipOf(key: String): String? =
        jdbc.queryForObject("SELECT host(ip) FROM agents WHERE agent_key = ?", String::class.java, key)

    fun updatedAtOf(key: String): Instant =
        jdbc.queryForObject("SELECT updated_at FROM agents WHERE agent_key = ?", java.sql.Timestamp::class.java, key)!!.toInstant()

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

            Then("주소 메모가 없는 메시지였으니 ip 는 비어 있다") {
                ipOf(key) shouldBe null
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
            registry.register(listOf(sighting("not-registered-${System.nanoTime()}", key, ip = "172.19.0.23")))

            Then("주소가 있어도 줄이 생기지 않는다 — applications 에 이름이 없으면 FK 를 채울 수 없다") {
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

    // 주소는 다른 칸과 성격이 다르다. 처음 값이 영구히 남으면 안 되고, 바뀌면 그 줄을 고쳐야 한다 (`#149`, ADR `#63`).
    // 적재 처리기가 이 표를 **고치는** 첫 자리다
    Given("주소가 담긴 목격") {
        val service = newService("shop-payment")

        When("처음 보는 파드를 주소와 함께 등록하면") {
            val key = "$service-pod-ip"
            registry.register(listOf(sighting(service, key, ip = "172.19.0.11")))

            Then("ip 칸에 그 주소가 들어간다") {
                ipOf(key) shouldBe "172.19.0.11"
            }
        }

        When("그 파드가 재생성돼 다른 주소로 다시 보이면") {
            val key = "$service-pod-moved"
            registry.register(listOf(sighting(service, key, ip = "172.19.0.12")))
            val before = counter.count(AgentRegisterCounter.Outcome.IP_UPDATED)
            registry.register(listOf(sighting(service, key, ip = "172.19.0.99", at = laterAt)))

            Then("줄은 늘지 않고 주소만 바뀐다 : 이름표가 같으면 같은 파드다") {
                rows(key) shouldBe 1
                ipOf(key) shouldBe "172.19.0.99"
            }

            Then("고친 시각도 같이 움직인다 : 상태를 고치는 쪽(탐지)과 모양을 맞췄다") {
                updatedAtOf(key) shouldBe laterAt
            }

            Then("고친 것을 센다") {
                counter.count(AgentRegisterCounter.Outcome.IP_UPDATED) shouldBe before + 1
            }
        }

        When("주소가 그대로인 목격이 또 들어오면") {
            val key = "$service-pod-same"
            registry.register(listOf(sighting(service, key, ip = "172.19.0.13")))
            val before = counter.count(AgentRegisterCounter.Outcome.IP_UPDATED)
            registry.register(listOf(sighting(service, key, ip = "172.19.0.13", at = laterAt)))

            Then("아무것도 고치지 않는다 : 캐시가 걸러 낸다") {
                counter.count(AgentRegisterCounter.Outcome.IP_UPDATED) shouldBe before
                updatedAtOf(key) shouldNotBe laterAt
            }

            Then("캐시가 비어 있어도 SQL 이 한 번 더 막는다 : IS DISTINCT FROM 이 0줄을 돌려준다") {
                // 캐시를 비운 직후(상한 초과)에는 모든 파드가 한 번씩 미스라 그때 쓰기가 몰린다. 그 자리를 SQL 로도 막아 뒀다
                repository.updateIp(agentKey = key, ip = "172.19.0.13", now = laterAt) shouldBe 0
                repository.updateIp(agentKey = key, ip = "172.19.0.77", now = laterAt) shouldBe 1
            }
        }

        When("주소를 채운 뒤 주소 메모가 없는 메시지가 오면") {
            val key = "$service-pod-noheader"
            registry.register(listOf(sighting(service, key, ip = "172.19.0.14")))
            registry.register(listOf(sighting(service, key, ip = null, at = laterAt)))

            Then("칸을 비우지 않는다 : 메모 없는 메시지(telemetrygen 등)가 멀쩡한 주소를 지우면 안 된다") {
                ipOf(key) shouldBe "172.19.0.14"
            }
        }
    }

    Given("파드 식별자나 서비스 이름이 없는 목격") {
        When("등록을 시도하면") {
            registry.register(
                listOf(
                    sighting("some-service", "", ip = "172.19.0.21"), // 파드 식별자 없음
                    sighting("", "orphan-pod", ip = "172.19.0.22"), // 서비스 이름 없음 (unknown_service 포함)
                ),
            )

            Then("주소가 있어도 아무 줄도 생기지 않는다 — ClickHouse 와 이을 수 없는 줄은 만들지 않는다") {
                rows("") shouldBe 0
                rows("orphan-pod") shouldBe 0
            }
        }
    }
})

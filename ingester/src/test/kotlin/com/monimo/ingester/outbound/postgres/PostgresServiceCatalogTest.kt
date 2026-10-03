package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

// 진짜 PostgreSQL 로 목록과 캐시 동작을 본다. TTL 을 짧게 준 인스턴스를 직접 만들어 30초를 기다리지 않는다
@SpringBootTest
@Import(TestInfraConfig::class)
class PostgresServiceCatalogTest(jdbc: JdbcTemplate) : BehaviorSpec({

    val ttl = Duration.ofMillis(300)
    fun newName(prefix: String): String {
        val name = "$prefix-${System.nanoTime()}"
        jdbc.update("INSERT INTO applications (name) VALUES (?)", name)
        return name
    }

    Given("등록된 서비스 하나와 제외된 서비스 하나") {
        val active = newName("catalog-active")
        val retired = newName("catalog-retired")
        jdbc.update("UPDATE applications SET deleted_at = now() WHERE name = ?", retired)
        val catalog = PostgresServiceCatalog(jdbc, ttl)

        When("목록을 읽으면") {
            val names = catalog.activeNames()

            Then("등록된 이름은 나오고 제외된 이름은 안 나온다") {
                names shouldContain active
                names shouldNotContain retired
            }
        }
    }

    Given("한 번 읽어 둔 목록") {
        val catalog = PostgresServiceCatalog(jdbc, ttl)
        catalog.activeNames()

        When("TTL 안에 서비스를 새로 등록하고 바로 다시 읽으면") {
            val late = newName("catalog-late")
            val within = catalog.activeNames()

            Then("아직 안 보인다 — PG 를 다시 치지 않았다 (캐시)") {
                within shouldNotContain late
            }

            Then("TTL 이 지나면 보인다") {
                Thread.sleep(ttl.toMillis() + 100)
                catalog.activeNames() shouldContain late
            }
        }
    }
})

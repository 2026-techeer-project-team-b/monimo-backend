package com.monimo.notifier

import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestInfraConfig::class)
class NotifierApplicationTest(
    environment: Environment,
    jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    Given("알림 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 notifier 로 잡힌다") {
                name shouldBe "notifier"
            }

            Then("db/postgres 마이그레이션이 적용된 PostgreSQL에 연결된다") {
                val applied = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success",
                    Int::class.java,
                )
                (applied!! >= 1) shouldBe true
            }
        }
    }
})

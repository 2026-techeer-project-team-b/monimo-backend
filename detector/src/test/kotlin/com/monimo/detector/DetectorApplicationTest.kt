package com.monimo.detector

import com.monimo.detector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestInfraConfig::class)
class DetectorApplicationTest(
    environment: Environment,
    jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    Given("탐지 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 detector 로 잡힌다") {
                name shouldBe "detector"
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

package com.monimo.api

import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@SpringBootTest
@Import(TestInfraConfig::class)
class ApiServerApplicationTest(
    environment: Environment,
    jdbcTemplate: JdbcTemplate,
    @Qualifier("clickHouseJdbcTemplate") clickHouseJdbcTemplate: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    Given("API 서버 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 api-server 로 잡힌다") {
                name shouldBe "api-server"
            }

            Then("db/postgres 마이그레이션이 적용된 PostgreSQL에 연결된다") {
                val applied = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success",
                    Int::class.java,
                )
                (applied!! >= 1) shouldBe true
            }

            Then("ClickHouse에 연결된다") {
                clickHouseJdbcTemplate.jdbcTemplate.queryForObject("SELECT 1", Int::class.java) shouldBe 1
            }
        }
    }
})

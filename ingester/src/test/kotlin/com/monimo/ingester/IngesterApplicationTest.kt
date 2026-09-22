package com.monimo.ingester

import com.clickhouse.client.api.Client
import com.monimo.ingester.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.AdminClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaAdmin

@SpringBootTest
@Import(TestInfraConfig::class)
class IngesterApplicationTest(
    environment: Environment,
    jdbcTemplate: JdbcTemplate,
    kafkaAdmin: KafkaAdmin,
    clickHouseClient: Client,
) : BehaviorSpec({

    Given("적재 처리기 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 ingester 로 잡힌다") {
                name shouldBe "ingester"
            }

            Then("db/postgres 마이그레이션이 적용된 PostgreSQL에 연결된다") {
                val applied = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success",
                    Int::class.java,
                )
                (applied!! >= 1) shouldBe true
            }

            Then("Kafka에 연결된다") {
                AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
                    admin.describeCluster().clusterId().get().shouldNotBeNull()
                }
            }

            Then("ClickHouse에 연결된다") {
                clickHouseClient.ping() shouldBe true
            }
        }
    }
})

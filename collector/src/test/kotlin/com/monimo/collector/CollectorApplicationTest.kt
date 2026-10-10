package com.monimo.collector

import com.monimo.collector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.AdminClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaAdmin

// 포트를 0(아무 빈 포트)으로 덮는다. 설정값 4317 을 그대로 쓰면 로컬에 수집기 컨테이너가 떠 있을 때
// "Address already in use" 로 항상 깨진다. OtlpGrpcReceiverTest 가 쓰는 것과 같은 방법이다
@SpringBootTest(properties = ["monimo.collector.otlp.grpc.port=0"])
@Import(TestInfraConfig::class)
class CollectorApplicationTest(
    environment: Environment,
    jdbcTemplate: JdbcTemplate,
    kafkaAdmin: KafkaAdmin,
) : BehaviorSpec({

    Given("수집기 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")

            Then("애플리케이션 이름이 collector 로 잡힌다") {
                name shouldBe "collector"
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
        }
    }
})

package com.monimo.ingester.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

// 테스트용 진짜 저장소. 스프링 테스트에서 @Import(TestInfraConfig::class) 로 붙인다.
// 컨테이너는 이 테스트 JVM 안에서 한 번 뜨고 테스트 끝나면 내려간다.
@TestConfiguration(proxyBeanMethods = false)
class TestInfraConfig {

    @Bean
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17.11-alpine").apply { start() }


    @Bean
    fun kafka(): KafkaContainer = KafkaContainer("apache/kafka:3.9.2").apply { start() }


    // db/clickhouse 의 DDL(표 11개 · MV 7개)을 처음 켤 때 적용한다. 테스트 작업 디렉터리는 ingester 모듈이다.
    // 적재 대상 표가 없으면 적재 코드를 검증할 수 없어서, api-server 테스트와 같은 방식으로 맞췄다
    @Bean
    fun clickhouse(): ClickHouseContainer = ClickHouseContainer("clickhouse/clickhouse-server:26.8.10.6")
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../db/clickhouse").toAbsolutePath().normalize()), "/docker-entrypoint-initdb.d/")
        .apply { start() }

    @Bean
    fun infraProperties(postgres: PostgreSQLContainer<*>, kafka: KafkaContainer, clickhouse: ClickHouseContainer) = DynamicPropertyRegistrar { registry ->
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        registry.add("monimo.clickhouse.endpoint") { "http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}" }
        // 운영 · local 과 같이 monimo DB 에 붙는다 (컨테이너 기본값은 default DB)
        registry.add("monimo.clickhouse.database") { "monimo" }
        registry.add("monimo.clickhouse.username", clickhouse::getUsername)
        registry.add("monimo.clickhouse.password", clickhouse::getPassword)
    }
}

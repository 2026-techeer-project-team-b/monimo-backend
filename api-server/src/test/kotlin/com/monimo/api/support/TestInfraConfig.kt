package com.monimo.api.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.PostgreSQLContainer

// 테스트용 진짜 저장소. 스프링 테스트에서 @Import(TestInfraConfig::class) 로 붙인다.
// 컨테이너는 이 테스트 JVM 안에서 한 번 뜨고 테스트 끝나면 내려간다.
@TestConfiguration(proxyBeanMethods = false)
class TestInfraConfig {

    @Bean
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17.11-alpine").apply { start() }


    @Bean
    fun clickhouse(): ClickHouseContainer = ClickHouseContainer("clickhouse/clickhouse-server:26.8.10.6").apply { start() }

    @Bean
    fun infraProperties(postgres: PostgreSQLContainer<*>, clickhouse: ClickHouseContainer) = DynamicPropertyRegistrar { registry ->
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("monimo.clickhouse.jdbc-url", clickhouse::getJdbcUrl)
        registry.add("monimo.clickhouse.username", clickhouse::getUsername)
        registry.add("monimo.clickhouse.password", clickhouse::getPassword)
    }
}

package com.monimo.api.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

// 테스트용 진짜 저장소. 스프링 테스트에서 @Import(TestInfraConfig::class) 로 붙인다.
// 컨테이너는 이 테스트 JVM 안에서 한 번 뜨고 테스트 끝나면 내려간다.
@TestConfiguration(proxyBeanMethods = false)
class TestInfraConfig {

    @Bean
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17.11-alpine").apply { start() }


    // db/clickhouse 의 DDL(표 · MV)을 처음 켤 때 적용한다. 테스트 작업 디렉터리는 api-server 모듈이다
    @Bean
    fun clickhouse(): ClickHouseContainer = ClickHouseContainer("clickhouse/clickhouse-server:26.8.10.6")
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../db/clickhouse").toAbsolutePath().normalize()), "/docker-entrypoint-initdb.d/")
        .apply { start() }

    @Bean
    fun infraProperties(postgres: PostgreSQLContainer<*>, clickhouse: ClickHouseContainer) = DynamicPropertyRegistrar { registry ->
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        // 운영 · local 과 같이 monimo DB 에 붙는다 (컨테이너 기본값은 default DB)
        registry.add("monimo.clickhouse.jdbc-url") { clickhouse.jdbcUrl.substringBeforeLast('/') + "/monimo" }
        registry.add("monimo.clickhouse.username", clickhouse::getUsername)
        registry.add("monimo.clickhouse.password", clickhouse::getPassword)
    }
}

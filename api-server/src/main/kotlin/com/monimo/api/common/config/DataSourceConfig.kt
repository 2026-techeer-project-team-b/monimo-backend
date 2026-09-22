package com.monimo.api.common.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

// API 서버는 DB 두 개에 붙는다.
// - PostgreSQL (기본, @Primary): 설정 · 규칙 · 채널 · 사용자. JPA가 쓴다 (ADR #42)
// - ClickHouse (조회 전용): 스팬 · 지표 · 로그. clickHouseJdbcTemplate 으로 SQL을 직접 쓴다
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun postgresDataSourceProperties() = DataSourceProperties()

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    fun postgresDataSource(postgresDataSourceProperties: DataSourceProperties): HikariDataSource =
        postgresDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    @ConfigurationProperties("monimo.clickhouse")
    fun clickHouseDataSource(): HikariDataSource = HikariDataSource().apply { poolName = "clickhouse" }

    @Bean
    fun clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") clickHouseDataSource: DataSource) =
        NamedParameterJdbcTemplate(clickHouseDataSource)
}

package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("monimo.clickhouse")
data class ClickHouseProperties(
    val endpoint: String,
    val database: String,
    val username: String,
    val password: String,
)

// 적재용 ClickHouse 클라이언트 (client-v2). 대량 insert는 이걸로 한다.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseProperties::class)
class ClickHouseClientConfig {

    @Bean(destroyMethod = "close")
    fun clickHouseClient(properties: ClickHouseProperties): Client =
        Client.Builder()
            .addEndpoint(properties.endpoint)
            .setUsername(properties.username)
            .setPassword(properties.password)
            .setDefaultDatabase(properties.database)
            .build()
}

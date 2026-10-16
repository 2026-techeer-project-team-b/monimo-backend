package com.monimo.api.selfcheck

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 다른 모듈의 관리 포트에서 게이지 값 하나를 읽는다. 못 읽으면 null (죽었거나 주소가 틀렸거나 게이지가 없다)
fun interface GaugeReader {
    fun read(managementUrl: String, gauge: String): Double?
}

// GET {관리 포트}/actuator/metrics/{게이지} 의 measurements[0].value. 관리 포트는 토큰이 없다
class HttpGaugeReader(timeout: Duration, private val objectMapper: ObjectMapper) : GaugeReader {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val timeout = timeout

    override fun read(managementUrl: String, gauge: String): Double? {
        val request = HttpRequest.newBuilder(URI.create("${managementUrl.trimEnd('/')}/actuator/metrics/$gauge"))
            .timeout(timeout)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return null
        }
        if (response.statusCode() != 200) return null
        return try {
            objectMapper.readTree(response.body())["measurements"]?.firstOrNull()?.get("value")?.asDouble()
        } catch (e: Exception) {
            null
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SelfCheckProperties::class)
class SelfCheckConfig {
    @Bean
    fun gaugeReader(props: SelfCheckProperties, objectMapper: ObjectMapper): GaugeReader = HttpGaugeReader(props.gaugeTimeout, objectMapper)
}

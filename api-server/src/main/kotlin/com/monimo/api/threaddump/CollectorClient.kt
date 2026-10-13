package com.monimo.api.threaddump

import com.monimo.api.common.security.InternalTokenProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

// 수집기 호출 설정 (application.yml monimo.collector). urls 는 팬아웃 대상 전부 — 1a 는 compose 이름 하나, K8s 는 헤드리스 Service 의 파드 주소들
@ConfigurationProperties("monimo.collector")
data class CollectorClientProperties(
    val urls: List<String> = listOf("http://localhost:8081"),
    val connectTimeout: Duration = Duration.ofSeconds(2),
    // 수집기가 timeout_ms 를 스스로 지키고 늦으면 504 를 주므로, 여기는 그보다 조금만 길면 된다
    val responseMargin: Duration = Duration.ofSeconds(2),
)

// 수집기 한 대의 답. 팬아웃은 수집기 수만큼 이 값을 받는다
sealed interface DumpOutcome {
    // 200. 본문은 Extension 결과 JSON 그대로 (service · instance · taken_at · elapsed_ms · thread_count · format · dump)
    data class Dumped(val body: String) : DumpOutcome
    // 503 AGENT_NOT_REACHABLE : 이 수집기는 그 에이전트의 폴링을 쥐고 있지 않다
    data object NotReachable : DumpOutcome
    // 504 THREAD_DUMP_TIMEOUT : 에이전트를 쥐고는 있는데 시간 안에 덤프가 안 왔다
    data object Timeout : DumpOutcome
    // 연결 실패 · 그 밖의 응답. 수집기가 죽었거나 주소가 틀렸다
    data class Failed(val reason: String) : DumpOutcome
}

fun interface CollectorClient {
    fun threadDump(service: String, instance: String, timeout: Duration): List<DumpOutcome>
}

// 수집기 내부 문 POST /internal/thread-dump 를 전부에 동시에 보낸다 (#122 계약). 판단은 하지 않고 답만 모아 준다
class HttpCollectorClient(
    private val props: CollectorClientProperties,
    private val internalToken: String,
) : CollectorClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    // 순차로 보내면 수집기 N대 × timeout 이 된다. Java 17 이라 가상 스레드는 못 쓴다
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "collector-fanout").apply { isDaemon = true } }

    override fun threadDump(service: String, instance: String, timeout: Duration): List<DumpOutcome> {
        val body = """{"service":${quote(service)},"instance":${quote(instance)},"timeout_ms":${timeout.toMillis()}}"""
        val futures = props.urls.map { url -> CompletableFuture.supplyAsync({ send(url, body, timeout) }, executor) }
        return futures.map { it.join() }
    }

    private fun send(url: String, body: String, timeout: Duration): DumpOutcome {
        val request = HttpRequest.newBuilder(URI.create("${url.trimEnd('/')}$PATH"))
            .timeout(timeout.plus(props.responseMargin))
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", internalToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return DumpOutcome.Failed("$url: ${e.javaClass.simpleName}")
        }
        return when (response.statusCode()) {
            200 -> DumpOutcome.Dumped(response.body())
            503 -> DumpOutcome.NotReachable
            504 -> DumpOutcome.Timeout
            else -> DumpOutcome.Failed("$url: HTTP ${response.statusCode()}")
        }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val PATH = "/internal/thread-dump"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectorClientProperties::class)
class CollectorClientConfig {
    @Bean
    fun collectorClient(props: CollectorClientProperties, token: InternalTokenProperties): CollectorClient =
        HttpCollectorClient(props, token.internalToken)
}

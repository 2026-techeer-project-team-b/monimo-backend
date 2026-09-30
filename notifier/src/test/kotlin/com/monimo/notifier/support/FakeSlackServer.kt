package com.monimo.notifier.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// 가짜 Slack 수신 서버. 발신 쪽 로그가 아니라 "실제로 받은 것"을 센다.
// 주소 /hook/{mode}: ok · 429 · 503 · 400 · slow(응답 전에 오래 멈춤) · switch(switchStatus 로 응답)
class FakeSlackServer : AutoCloseable {
    val received = ConcurrentLinkedQueue<String>()
    val hits = AtomicInteger()
    @Volatile var slowMillis = 2_000L
    @Volatile var retryAfterSeconds = "120"
    // /hook/switch 의 응답. 도중에 바꿔 "채널이 죽었다가 살아났다"를 만든다 (E8)
    @Volatile var switchStatus = 503

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook/") { ex ->
            hits.incrementAndGet()
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val (status, text) = when (ex.requestURI.path.removePrefix("/hook/")) {
                "ok" -> { received.add(body); 200 to "ok" }
                "429" -> { ex.responseHeaders.add("Retry-After", retryAfterSeconds); 429 to "rate_limited" }
                "503" -> 503 to "service_unavailable"
                "switch" -> if (switchStatus == 200) { received.add(body); 200 to "ok" } else switchStatus to "service_unavailable"
                "400" -> 400 to "invalid_payload"
                "slow" -> { received.add(body); Thread.sleep(slowMillis); 200 to "ok" }  // 받긴 받았는데 답이 늦다
                else -> 404 to "channel_not_found"
            }
            val bytes = text.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        start()
    }

    fun url(mode: String) = "http://127.0.0.1:${server.address.port}/hook/$mode"

    fun reset() { received.clear(); hits.set(0) }

    override fun close() = server.stop(0)
}

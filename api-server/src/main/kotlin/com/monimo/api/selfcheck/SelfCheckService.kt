package com.monimo.api.selfcheck

import com.monimo.api.selfcheck.dto.AgeCheck
import com.monimo.api.selfcheck.dto.PostgresCheck
import com.monimo.api.selfcheck.dto.SelfCheckResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

// PG 두드림 + 탐지 · 수집기 낡음 게이지. 파수꾼이 밖에서 "PG 가 죽었다 · 탐지가 멈췄다" 를 볼 유일한 문 (#226, #208 ⑥)
@Service
class SelfCheckService(
    dataSource: DataSource,
    private val gauges: GaugeReader,
    private val props: SelfCheckProperties,
) {
    private val jdbc = JdbcTemplate(dataSource)

    // PG 가 죽으면 연결을 얻는 데 Hikari connectionTimeout(30초)까지 걸린다. 파수꾼이 그만큼 기다리게 하지 않으려고
    // 다른 스레드에서 두드리고 기한만큼만 기다린다. 기한을 넘긴 스레드는 Hikari 가 포기할 때 알아서 끝난다
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "self-check-pg").apply { isDaemon = true } }

    fun check(): SelfCheckResponse = SelfCheckResponse(
        postgres = pingPostgres(),
        detector = age(props.detectorManagementUrl, DETECTOR_GAUGE, props.detectorMaxAge),
        collector = age(props.collectorManagementUrl, COLLECTOR_GAUGE, props.collectorMaxAge),
    )

    private fun pingPostgres(): PostgresCheck {
        val started = System.nanoTime()
        val elapsed = { (System.nanoTime() - started) / 1_000_000 }
        val future = CompletableFuture.supplyAsync({ jdbc.queryForObject("SELECT 1", Int::class.java) }, executor)
        return try {
            future.get(props.postgresTimeout.toMillis(), TimeUnit.MILLISECONDS)
            PostgresCheck(ok = true, ms = elapsed())
        } catch (e: TimeoutException) {
            PostgresCheck(ok = false, ms = elapsed(), error = "timeout")
        } catch (e: ExecutionException) {
            PostgresCheck(ok = false, ms = elapsed(), error = (e.cause ?: e).javaClass.simpleName)
        }
    }

    private fun age(managementUrl: String, gauge: String, max: Duration): AgeCheck {
        val value = gauges.read(managementUrl, gauge)
            ?: return AgeCheck(ok = false, ageSec = null, maxSec = max.seconds, error = "unreachable")
        return AgeCheck(ok = value <= max.seconds, ageSec = value, maxSec = max.seconds)
    }

    private companion object {
        // 탐지 · 수집기가 ADR #54 로 내놓는 "마지막 성공 뒤 흐른 초"
        const val DETECTOR_GAUGE = "monimo.detector.evaluation.age"
        const val COLLECTOR_GAUGE = "monimo.collector.sampling.refresh.age"
    }
}

package com.monimo.collector.outbound.postgres

import com.monimo.collector.sampling.SamplingProperties
import com.monimo.collector.sampling.SamplingRateSource
import com.monimo.collector.sampling.SamplingRates
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

// SamplingRateSource 의 PostgreSQL 구현(어댑터). application_configs 를 읽기만 한다.
//
// 이 모듈에 Entity 를 두지 않는다: 표 주인이 API 서버이고(ADR #36), 수집기는
// ddl-auto: validate 라 Entity 가 생기면 그 검증까지 걸린다. JdbcTemplate 으로 SQL 한 줄을 친다.
//
// **읽기를 OTLP 요청 경로에서 하지 않는다.** 적재 처리기의 PostgresServiceCatalog 는 물어볼 때
// 낡았으면 그 자리에서 읽는데, 그걸 그대로 베끼면 PG 가 죽었을 때 그 요청이 커넥션 풀 대기만큼
// 멈춘다 (로컬 실측 : OTLP 요청 하나가 10초). 적재 처리기는 Kafka 컨슈머라 견디지만 수집기는
// 에이전트가 응답을 기다리고 있고 수집 경로 가용성 목표가 조회보다 높다(99.9% 대 99.5%).
// 그래서 @Scheduled 가 주기로 읽어 스냅샷만 갈아끼우고, rates() 는 들고 있는 값을 바로 준다.
// PG 가 죽으면 비율이 낡기만 하고 수집은 한 번도 멈추지 않는다 (ADR #53).
@Component
class PostgresSamplingRateSource(
    private val jdbc: JdbcTemplate,
    private val properties: SamplingProperties,
    registry: MeterRegistry,
) : SamplingRateSource {

    // 여러 스레드가 동시에 읽고 갈아끼워도 안전한 참조 하나.
    // 처음엔 PG 를 안 읽은 상태라 yml 기본값으로 둔다: 빈 값을 쓰면 비율이 0(전부 버림)이나
    // 1(전부 통과)이 되는데 둘 다 사고다
    private val snapshot = AtomicReference(SamplingRates.unread(properties.ratio))

    // 이 설계의 약속이 "PG 가 죽어도 수집은 돌고 비율만 낡는다" 인데, 낡았다는 사실을 로그 한 줄로만
    // 알 수 있으면 아무도 모른다. 탐지 · 알림이 걸 수 있게 센다 (헬스체크 필터 · 미등록 카운터와 같은 기준)
    private val refreshed = counter(registry, "success", "샘플링 비율 갱신에 성공한 횟수")
    private val failed = counter(registry, "failure", "샘플링 비율 갱신에 실패한 횟수")

    override fun rates(): SamplingRates = snapshot.get()

    // fixedDelay 라 앱이 뜬 직후 한 번 돌고 그다음부터 ttl 간격으로 돈다.
    // 갱신에 실패하면 들고 있던 값을 그대로 두고 다음 차례에 다시 시도한다
    @Scheduled(fixedDelayString = "\${monimo.collector.sampling.ttl:30s}")
    fun refresh() {
        val current = snapshot.get()
        val fresh = runCatching { load() }.getOrElse { error ->
            failed.increment()
            log.warn("샘플링 비율 갱신 실패 : 비율 {} 유지 ({})", current.applied, error.message)
            return
        }
        snapshot.set(fresh)
        refreshed.increment()
        if (fresh.byService.isEmpty()) {
            // 조회는 됐는데 줄이 없다. 비율이 기본값으로 떨어지는데 "못 읽었다" 와 증상이 같아서
            // 이것을 안 찍으면 운영에서 조용히 기본값으로 돈다 (seed 미실행 · 전부 deleted_at · 엉뚱한 DB)
            log.warn("샘플링 비율 : 감시 중인 서비스가 0개다. 기본값 {} 로 돈다", fresh.applied)
        } else if (fresh.byService != current.byService) {
            log.info("샘플링 비율 갱신 : 적용 {} (서비스별 {})", fresh.applied, fresh.byService.toSortedMap())
        }
    }

    // 제외된(deleted_at) 서비스는 감시 대상이 아니므로 비율에 넣지 않는다.
    // queryForList 로 받는 이유: 줄이 몇 개 안 되고, 이 모듈에 Entity 도 RowMapper 도 두지 않는다
    private fun load(): SamplingRates =
        SamplingRates.of(
            jdbc.queryForList(SQL).associate { row ->
                row["name"] as String to (row["sampling_rate"] as Number).toDouble()
            },
            properties.ratio,
        )

    private fun counter(registry: MeterRegistry, outcome: String, description: String): Counter =
        Counter.builder(METRIC).description(description).tag("outcome", outcome).register(registry)

    private companion object {

        const val METRIC = "monimo.collector.sampling.refresh"

        val log = LoggerFactory.getLogger(PostgresSamplingRateSource::class.java)

        const val SQL =
            "SELECT a.name, c.sampling_rate FROM application_configs c " +
                "JOIN applications a ON a.id = c.application_id WHERE a.deleted_at IS NULL"
    }
}

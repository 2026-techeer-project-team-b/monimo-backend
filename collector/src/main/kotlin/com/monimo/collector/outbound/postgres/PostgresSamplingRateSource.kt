package com.monimo.collector.outbound.postgres

import com.monimo.collector.sampling.SamplingProperties
import com.monimo.collector.sampling.SamplingRateSource
import com.monimo.collector.sampling.SamplingRates
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// SamplingRateSource 의 PostgreSQL 구현(어댑터). application_configs 를 읽기만 한다.
//
// 이 모듈에 Entity 를 두지 않는다: 표 주인이 API 서버이고(ADR #36), 수집기는
// ddl-auto: validate 라 Entity 가 생기면 그 검증까지 걸린다. JdbcTemplate 으로 SQL 한 줄을 친다.
//
// 캐시: 서비스는 몇 개 안 되므로 한 벌을 통째로 들고 30초마다 바꾼다 (ADR #37).
// 화면에서 비율을 바꾸면 최대 30초 안에 반영된다. 적재 처리기의 PostgresServiceCatalog 와 같은 모양이고
// 다른 점은 첫 조회 실패다: 그쪽은 빈 집합이 안전한 쪽("모르는 주소는 EXTERNAL")이었지만
// 비율은 빈 값이 0(전부 버림)이나 1(전부 통과)이 되어 둘 다 사고라 yml 기본값으로 떨어진다.
@Component
class PostgresSamplingRateSource(
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val properties: SamplingProperties,
) : SamplingRateSource {

    private class Snapshot(val rates: SamplingRates, val loadedAt: Instant)

    // 처음엔 PG 를 안 읽은 상태라 yml 기본값으로 둔다. 시각이 아주 과거라 첫 호출에 바로 읽는다
    private val snapshot = AtomicReference(
        Snapshot(SamplingRates.of(emptyMap(), properties.ratio), Instant.EPOCH),
    )

    override fun rates(): SamplingRates {
        val current = snapshot.get()
        val now = Instant.now()
        if (Duration.between(current.loadedAt, now) < properties.ttl) return current.rates

        // 갱신에 실패하면 들고 있던 값을 그대로 쓴다. PG 가 잠깐 죽어도 수집은 계속 돈다
        val fresh = runCatching { load() }.getOrElse { error ->
            log.warn("샘플링 비율 갱신 실패 : 비율 {} 유지 ({})", current.rates.applied, error.message)
            // 실패 시각으로 갱신해 두어 요청마다 PG 를 다시 치지 않게 한다 (다음 TTL 뒤 재시도)
            snapshot.set(Snapshot(current.rates, now))
            return current.rates
        }
        snapshot.set(Snapshot(fresh, now))
        if (fresh.byService != current.rates.byService) {
            log.info("샘플링 비율 갱신 : 적용 {} (서비스별 {})", fresh.applied, fresh.byService.toSortedMap())
        }
        return fresh
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

    private companion object {

        val log = LoggerFactory.getLogger(PostgresSamplingRateSource::class.java)

        const val SQL =
            "SELECT a.name, c.sampling_rate FROM application_configs c " +
                "JOIN applications a ON a.id = c.application_id WHERE a.deleted_at IS NULL"
    }
}

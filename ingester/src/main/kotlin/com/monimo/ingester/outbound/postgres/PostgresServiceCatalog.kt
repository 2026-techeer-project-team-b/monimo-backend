package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.transform.ServiceCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// ServiceCatalog 의 PostgreSQL 구현(어댑터). applications 표를 읽기만 한다.
//
// applications 는 API 서버 것이라 이 모듈에 Entity 가 없다(만들면 표 주인 규칙에 어긋난다).
// 이름 목록만 필요하니 JdbcTemplate 으로 SQL 한 줄을 친다.
//
// 캐시: 서비스는 몇 개 안 되므로 목록 전체를 한 벌 들고 30초마다 통째로 바꾼다.
// J(#66) 의 "등록한 키 집합" 과 달리 크기가 안 커서 상한이 필요 없다.
// 화면에서 서비스를 새로 등록하면 최대 30초 안에 반영된다 (샘플링 비율 캐시와 같은 주기)
@Component
class PostgresServiceCatalog(
    private val jdbc: JdbcTemplate,
    // 테스트에서 짧게 바꿀 수 있게 설정으로 뺐다. 운영 기본 30초
    @Value("\${monimo.ingester.service-catalog.ttl:30s}") private val ttl: Duration,
) : ServiceCatalog {

    private class Snapshot(val names: Set<String>, val loadedAt: Instant)

    // 여러 스레드가 동시에 읽고 갈아끼워도 안전한 "참조 하나". 처음엔 빈 목록 · 아주 오래된 시각이라 첫 호출에 바로 읽는다
    private val snapshot = AtomicReference(Snapshot(emptySet(), Instant.EPOCH))

    override fun activeNames(): Set<String> {
        val current = snapshot.get()
        val now = Instant.now()
        if (Duration.between(current.loadedAt, now) < ttl) return current.names

        // 갱신에 실패하면 이전 목록을 그대로 쓴다. PG 가 잠깐 죽어도 서버맵이 갑자기 EXTERNAL 로 바뀌지 않는다.
        // 처음 로드부터 실패면 빈 집합이라 아무것도 채우지 않고, 적재는 계속된다
        val fresh = runCatching { load() }.getOrElse { error ->
            log.warn("서비스 목록 갱신 실패 — 이전 목록 {}개 유지: {}", current.names.size, error.message)
            // 실패 시각으로 갱신해 두어 매 메시지마다 PG 를 다시 치지 않게 한다 (다음 TTL 뒤 재시도)
            snapshot.set(Snapshot(current.names, now))
            return current.names
        }
        snapshot.set(Snapshot(fresh, now))
        if (fresh != current.names) log.info("서비스 목록 갱신 — {}개: {}", fresh.size, fresh.sorted())
        return fresh
    }

    private fun load(): Set<String> =
        jdbc.queryForList("SELECT name FROM applications WHERE deleted_at IS NULL", String::class.java).toSet()

    private companion object {
        val log = LoggerFactory.getLogger(PostgresServiceCatalog::class.java)
    }
}

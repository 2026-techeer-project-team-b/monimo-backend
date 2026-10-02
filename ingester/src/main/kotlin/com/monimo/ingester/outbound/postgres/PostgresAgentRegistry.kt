package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.outbound.postgres.AgentRegisterCounter.Outcome
import com.monimo.ingester.transform.AgentRegistry
import com.monimo.ingester.transform.AgentSighting
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// AgentRegistry 의 PostgreSQL 구현(어댑터). 도메인(transform)은 이 파일을 모른다.
//
// 신호가 올 때마다 PG 를 치면 초당 수천 번이 된다. 그래서 이미 등록한 파드 키를 메모리에 들고
// 처음 보는 것만 PG 에 넣는다. 캐시가 틀려도(미스) ON CONFLICT 가 받아 주므로 PG 를 한 번 더 치는 것뿐이다.
@Component
class PostgresAgentRegistry(
    private val repository: AgentRepository,
    private val counter: AgentRegisterCounter,
) : AgentRegistry {

    // 이미 PG 에 있다고 확인된 파드 키. Kafka 리스너가 여러 스레드일 수 있어 동시에 써도 안전한 Set 을 쓴다
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun register(sightings: Collection<AgentSighting>) {
        // 등록할 수 없는 것(파드 식별자 없음 · 서비스 이름 없음)과 이미 아는 것을 먼저 걸러 낸다.
        // distinctBy = 한 메시지에 같은 파드가 여러 번 들어 있어도 한 번만 시도한다
        val fresh = sightings.filter { it.registrable && it.agentKey !in known }.distinctBy { it.agentKey }
        if (fresh.isEmpty()) return

        for (sighting in fresh) {
            // 한 파드가 실패해도 나머지는 시도한다. 등록은 적재의 곁다리라 전체를 멈추지 않는다
            runCatching { insert(sighting) }
                .onFailure { error ->
                    counter.increment(Outcome.FAILED)
                    log.warn("파드 등록 실패 — {} / {}: {}", sighting.serviceName, sighting.agentKey, error.message)
                }
        }
    }

    // 파드 하나를 넣는다. 트랜잭션은 AgentRepository.insertIfAbsent 에 걸려 있어 호출마다 하나씩 열린다.
    // 그래서 한 파드가 실패해도 앞서 넣은 파드가 되돌려지지 않는다
    private fun insert(sighting: AgentSighting) {
        val inserted = repository.insertIfAbsent(
            serviceName = sighting.serviceName,
            agentKey = sighting.agentKey,
            hostname = sighting.hostname,
            jvmVersion = sighting.jvmVersion,
            agentVersion = sighting.agentVersion,
            // 신호 안의 시각이 아니라 본 시각을 쓴다. 에이전트 시계가 틀릴 수 있고,
            // 배포 시점을 가늠하는 칸이라 초 단위 정확성이 필요 없다. ON CONFLICT DO NOTHING 이라 처음 값이 남는다
            firstSeenAt = sighting.seenAt,
        )

        if (inserted > 0) {
            known += sighting.agentKey
            counter.increment(Outcome.REGISTERED)
            log.info("파드 등록 — {} / {}", sighting.serviceName, sighting.agentKey)
            trimCache()
            return
        }

        // 0줄이면 두 경우다. 이미 있으면 캐시에 넣어 다음부터 PG 를 안 치고,
        // 모르는 서비스면 캐시에 넣지 않는다 — 화면에서 서비스를 등록하면 다음 메시지에서 들어가야 하니까
        if (repository.countService(sighting.serviceName) > 0) {
            known += sighting.agentKey
            trimCache()
        } else {
            counter.increment(Outcome.UNKNOWN_SERVICE)
            // WARN 이 아니라 DEBUG 다. 등록되지 않은 서비스(telemetrygen 등)가 계속 보내면 로그가 뒤덮인다
            log.debug("등록되지 않았거나 제외된 서비스의 파드라 건너뜀 — {} / {}", sighting.serviceName, sighting.agentKey)
        }
    }

    // 파드가 계속 교체되면(배포마다 새 파드) 캐시가 무한히 커진다. 상한을 넘으면 비운다.
    // 비워도 안전하다 — 다음 미스는 ON CONFLICT 로 끝나고 줄이 늘지 않는다
    private fun trimCache() {
        if (known.size > MAX_KNOWN) {
            log.info("파드 캐시 {}개를 비운다 (상한 {})", known.size, MAX_KNOWN)
            known.clear()
        }
    }

    private companion object {
        const val MAX_KNOWN = 10_000
        val log = LoggerFactory.getLogger(PostgresAgentRegistry::class.java)
    }
}

package com.monimo.ingester.outbound.postgres

import com.monimo.ingester.outbound.postgres.AgentRegisterCounter.Outcome
import com.monimo.ingester.transform.AgentRegistry
import com.monimo.ingester.transform.AgentSighting
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// AgentRegistry 의 PostgreSQL 구현(어댑터). 도메인(transform)은 이 파일을 모른다.
//
// 신호가 올 때마다 PG 를 치면 초당 수천 번이 된다. 그래서 이미 등록한 파드를 메모리에 들고
// 처음 보는 것만 PG 에 넣는다. 캐시가 틀려도(미스) ON CONFLICT 가 받아 주므로 PG 를 한 번 더 치는 것뿐이다.
//
// 기억하는 것이 "이름표" 에서 "이름표 → 마지막으로 본 주소" 짝으로 바뀌었다(`#149`, ADR `#63`).
// 주소는 다른 칸과 달리 바뀌므로, 아는 파드여도 주소가 달라지면 한 번 고쳐야 한다
@Component
class PostgresAgentRegistry(
    private val repository: AgentRepository,
    private val counter: AgentRegisterCounter,
) : AgentRegistry {

    // 이미 PG 에 있다고 확인된 파드 → 그 파드에 대해 마지막으로 PG 에 적은 주소.
    // Kafka 리스너가 여러 스레드일 수 있어 동시에 써도 안전한 Map 을 쓴다.
    // ConcurrentHashMap 은 null 값을 못 담아서 "줄은 있는데 주소는 모른다" 를 빈 글자로 표시한다 (진짜 주소는 빈 글자일 수 없다)
    private val known: MutableMap<String, String> = ConcurrentHashMap()

    override fun register(sightings: Collection<AgentSighting>) {
        // 등록할 수 없는 것(파드 식별자 없음 · 서비스 이름 없음)과 할 일이 없는 것을 먼저 걸러 낸다.
        // distinctBy = 한 메시지에 같은 파드가 여러 번 들어 있어도 한 번만 시도한다
        val fresh = sightings.filter { it.registrable && it.needsWrite() }.distinctBy { it.agentKey }
        if (fresh.isEmpty()) return

        for (sighting in fresh) {
            // 한 파드가 실패해도 나머지는 시도한다. 등록은 적재의 곁다리라 전체를 멈추지 않는다
            runCatching {
                // 아는 파드면 줄이 이미 있으니 주소만 고친다(쿼리 하나). 모르는 파드면 넣는다
                if (sighting.agentKey in known) refreshIp(sighting) else insert(sighting)
            }.onFailure { error ->
                counter.increment(Outcome.FAILED)
                log.warn("파드 등록 실패 — {} / {}: {}", sighting.serviceName, sighting.agentKey, error.message)
            }
        }
    }

    // PG 를 쳐야 하는 목격인가. 둘 중 하나다 : 처음 보는 파드 · 아는 파드인데 주소가 달라짐.
    // 주소를 모르는(메모가 없는) 메시지는 아는 파드라면 할 일이 없다. 칸을 NULL 로 되돌리지 않는다 :
    // 주소 메모가 없는 메시지(telemetrygen 등)가 섞여 들어와 멀쩡한 주소를 지우면 안 된다
    private fun AgentSighting.needsWrite(): Boolean {
        val lastSeen = known[agentKey] ?: return true // 처음 보는 파드
        return ip != null && ip != lastSeen
    }

    // 파드 하나를 넣는다. 트랜잭션은 AgentRepository.insertIfAbsent 에 걸려 있어 호출마다 하나씩 열린다.
    // 그래서 한 파드가 실패해도 앞서 넣은 파드가 되돌려지지 않는다
    private fun insert(sighting: AgentSighting) {
        val inserted = repository.insertIfAbsent(
            serviceName = sighting.serviceName,
            agentKey = sighting.agentKey,
            hostname = sighting.hostname,
            ip = sighting.ip, // 수집기가 봉투 메모로 보내 준 주소. 없으면 null 이고 칸이 빈다
            jvmVersion = sighting.jvmVersion,
            agentVersion = sighting.agentVersion,
            // 신호 안의 시각이 아니라 본 시각을 쓴다. 에이전트 시계가 틀릴 수 있고,
            // 배포 시점을 가늠하는 칸이라 초 단위 정확성이 필요 없다. ON CONFLICT DO NOTHING 이라 처음 값이 남는다
            firstSeenAt = sighting.seenAt,
        )

        if (inserted > 0) {
            known[sighting.agentKey] = sighting.ip ?: NO_IP
            trimCache()
            counter.increment(Outcome.REGISTERED)
            log.info("파드 등록 — {} / {} (주소 {})", sighting.serviceName, sighting.agentKey, sighting.ip ?: "없음")
            return
        }

        // 0줄이면 두 경우다. 이미 있으면 캐시에 넣어 다음부터 PG 를 안 치고,
        // 모르는 서비스면 캐시에 넣지 않는다 — 화면에서 서비스를 등록하면 다음 메시지에서 들어가야 하니까
        if (repository.countService(sighting.serviceName) > 0) {
            // 줄은 있는데 우리가 안 넣은 것이다(다른 적재 처리기가 넣었거나 재시작 전에 넣었다).
            // 그 줄의 주소가 지금 주소와 같은지 알 수 없으므로 "모른다" 로 기억해 두고, 주소를 알면 바로 고친다
            known[sighting.agentKey] = NO_IP
            trimCache()
            if (sighting.ip != null) refreshIp(sighting)
        } else {
            counter.increment(Outcome.UNKNOWN_SERVICE)
            // WARN 이 아니라 DEBUG 다. 등록되지 않은 서비스(telemetrygen 등)가 계속 보내면 로그가 뒤덮인다
            log.debug("등록되지 않았거나 제외된 서비스의 파드라 건너뜀 — {} / {}", sighting.serviceName, sighting.agentKey)
        }
    }

    // 아는 파드의 주소를 고친다. 주소를 모르는 목격은 애초에 여기까지 오지 않는다(needsWrite).
    //
    // 서비스가 제외됐는지(deleted_at)는 보지 않는다 : 줄이 이미 있는 것을 고치는 일이고,
    // 이 표의 다른 갱신자(탐지의 status 변경)도 그 조건을 보지 않는다. 새 줄을 만드는 쪽만 그 조건을 본다
    private fun refreshIp(sighting: AgentSighting) {
        val ip = sighting.ip ?: return
        val updated = repository.updateIp(agentKey = sighting.agentKey, ip = ip, now = sighting.seenAt)
        // 기억은 결과와 무관하게 갱신한다. 0줄이면 PG 에 이미 그 값이 있다는 뜻이라 다음부터 안 쳐도 된다
        known[sighting.agentKey] = ip
        if (updated > 0) {
            counter.increment(Outcome.IP_UPDATED)
            log.info("파드 주소 변경 : {} / {} → {}", sighting.serviceName, sighting.agentKey, ip)
        }
    }

    // 파드가 계속 교체되면(배포마다 새 파드) 캐시가 무한히 커진다. 상한을 넘으면 비운다.
    // 비워도 안전하다 — 다음 미스는 ON CONFLICT 로 끝나고 줄이 늘지 않는다.
    // 주소도 같다 : 미스가 되면 UPDATE 를 한 번 더 치는데 SQL 의 IS DISTINCT FROM 이 값이 같으면 0줄로 끝낸다
    private fun trimCache() {
        if (known.size > MAX_KNOWN) {
            log.info("파드 캐시 {}개를 비운다 (상한 {})", known.size, MAX_KNOWN)
            known.clear()
        }
    }

    private companion object {
        const val MAX_KNOWN = 10_000
        // "줄은 있지만 주소는 모른다". 빈 글자는 주소가 될 수 없어 진짜 값과 섞이지 않는다
        const val NO_IP = ""
        val log = LoggerFactory.getLogger(PostgresAgentRegistry::class.java)
    }
}

package com.monimo.ingester.outbound.postgres

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 인터페이스만 두면 스프링이 구현을 만들어 준다
interface AgentRepository : JpaRepository<AgentEntity, Long> {

    // 처음 보는 파드면 한 줄 넣는다. 이미 있으면 아무 일도 안 한다.
    //
    // INSERT ... SELECT FROM applications WHERE name = :service
    //   application_id 가 NOT NULL FK 라 서비스 번호가 필요하다. 따로 조회하지 않고 같은 문장 안에서 찾는다.
    //   사람이 화면에서 등록하지 않은 서비스면 SELECT 가 0줄이라 INSERT 도 0줄이 된다(ADR #36).
    //
    // ON CONFLICT (agent_key) DO NOTHING
    //   같은 파드 메시지가 계속 들어와도 줄이 늘지 않는다. 적재 처리기를 여러 대 띄워 동시에 넣어도 한 줄만 남는다.
    //
    // CAST(:ip AS inet)
    //   ip 는 글자가 아니라 주소 전용 타입(INET)이다. 그냥 넘기면 드라이버가 글자로 보내 타입이 안 맞는다.
    //   null 도 그대로 넘어간다(CAST(NULL AS inet) = NULL). 주소를 모르는 메시지면 칸이 빈다.
    //   주소 글자에서 `::ffff:` 군더더기를 떼는 일은 수집기가 한다 (ADR `#63`)
    //
    // 돌려주는 값은 넣은 줄 수다. 0 이면 "이미 있음" 또는 "모르는 서비스" 두 경우다.
    // ADR #42 가드레일 ①: 선점 · 충돌 처리는 JPA 에 맡기지 않고 SQL 을 그대로 드러낸다 (탐지의 insertIfAbsent 와 같은 방식)
    // 쓰는 쿼리는 트랜잭션 안에서 돌아야 한다. 여기에 붙이면 스프링 데이터가 호출마다 열어 준다.
    // 부르는 쪽(PostgresAgentRegistry)에 붙이면 같은 클래스 안 호출이라 프록시를 안 거쳐 트랜잭션이 안 열린다
    @Transactional
    @Modifying // SELECT 가 아니라 쓰는 쿼리다
    @Query(
        nativeQuery = true, // JPQL 이 아니라 PostgreSQL SQL 그대로 (ON CONFLICT 는 JPQL 에 없다)
        value = """
            INSERT INTO agents (application_id, agent_key, hostname, ip, jvm_version, agent_version, first_seen_at)
            SELECT a.id, :agentKey, :hostname, CAST(:ip AS inet), :jvmVersion, :agentVersion, :firstSeenAt
            FROM applications a
            WHERE a.name = :serviceName AND a.deleted_at IS NULL
            ON CONFLICT (agent_key) DO NOTHING
        """,
    )
    fun insertIfAbsent(
        @Param("serviceName") serviceName: String,
        @Param("agentKey") agentKey: String,
        @Param("hostname") hostname: String?,
        @Param("ip") ip: String?,
        @Param("jvmVersion") jvmVersion: String?,
        @Param("agentVersion") agentVersion: String?,
        @Param("firstSeenAt") firstSeenAt: Instant,
    ): Int

    // 이미 있는 파드의 주소를 고친다. 주소는 다른 칸과 달리 바뀐다 : 파드 · 컨테이너가 재생성되면 새 주소를 받는데
    // 이름표를 고정해 둔 쪽(쇼핑몰 `#30`)은 같은 줄로 보이므로 옛 주소가 그대로 남는다. 빈칸이면 눈치채지만
    // 틀린 주소는 믿게 되므로 더 나쁘다 (ADR `#63` 채택 ③).
    //
    // IS DISTINCT FROM 을 SQL 에도 두는 이유 : 값이 같으면 PG 가 0줄을 돌려주고 쓰기가 아예 안 일어난다.
    // 부르는 쪽 캐시가 이미 걸러 주지만 캐시를 비운 직후(상한 초과)에는 모든 파드가 한 번씩 미스라 그때 쓰기가 몰린다.
    // `=` 가 아니라 IS DISTINCT FROM 인 이유는 NULL 비교다 : 옛 줄은 ip 가 NULL 이고 `NULL <> '1.2.3.4'` 는 참이 아니라 NULL 이다.
    //
    // updated_at 을 같이 고치는 이유 : 이 표의 다른 갱신자(탐지의 status 변경)가 이미 그렇게 한다. 모양을 하나로 맞췄다.
    // 돌려주는 값은 고친 줄 수다. 0 이면 "주소가 그대로" 또는 "그 파드가 없음" 이다
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            UPDATE agents
            SET ip = CAST(:ip AS inet), updated_at = :now
            WHERE agent_key = :agentKey AND ip IS DISTINCT FROM CAST(:ip AS inet)
        """,
    )
    fun updateIp(
        @Param("agentKey") agentKey: String,
        @Param("ip") ip: String,
        @Param("now") now: Instant,
    ): Int

    // 등록이 0줄이었을 때 "이미 있는 파드" 인지 "모르는 서비스" 인지 가른다. 카운터를 올릴지 정하는 데만 쓴다
    @Query(nativeQuery = true, value = "SELECT count(*) FROM applications WHERE name = :serviceName AND deleted_at IS NULL")
    fun countService(@Param("serviceName") serviceName: String): Long
}

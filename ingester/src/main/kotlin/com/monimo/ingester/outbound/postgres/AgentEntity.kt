package com.monimo.ingester.outbound.postgres

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// agents 표(db/postgres/ingest/V202609292300__create_agents.sql) 한 줄.
//
// 넣는 것은 아래 AgentRepository 의 네이티브 INSERT 한 문장이라 이 클래스로 저장하지는 않는다.
// 그래도 두는 이유: ddl-auto=validate 가 기동할 때 표와 이 클래스를 대조해서 어긋나면 서버를 안 띄운다.
// 적재 처리기가 agents 표의 주인이니(ADR #39) 표가 바뀌면 가장 먼저 알아야 한다.
//
// data class 가 아니라 class 다 (ADR #42). data class 는 모든 필드로 equals 를 만드는데
// JPA 는 PK 로 같음을 따지므로 어긋난다.
@Entity
@Table(name = "agents")
class AgentEntity(
    @Column(name = "application_id", nullable = false)
    val applicationId: Long,

    @Column(name = "agent_key", nullable = false, unique = true, length = 100)
    val agentKey: String,

    @Column(length = 255)
    val hostname: String?, // 표가 NULL 을 허용하므로 Kotlin 도 ?

    @Column(name = "jvm_version", length = 50)
    val jvmVersion: String?,

    @Column(name = "agent_version", length = 50)
    val agentVersion: String?,

    @Column(name = "first_seen_at")
    val firstSeenAt: Instant?,

    // 탐지가 CH 부재 규칙으로 UP · DOWN 을 쓴다(ADR #39). 우리는 기본값 UNKNOWN 으로 두고 건드리지 않는다
    @Column(nullable = false, length = 20)
    var status: String = "UNKNOWN",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.EPOCH, // 실제 값은 표의 DEFAULT now() 가 넣는다

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // BIGSERIAL = 번호를 DB 가 매긴다
    val id: Long = 0

    @Column(name = "agent_uuid", nullable = false, unique = true)
    val agentUuid: UUID = UUID.randomUUID() // 화면 주소에 나가는 값. 표의 DEFAULT gen_random_uuid() 와 같은 역할

    // ip(INET)는 매핑하지 않는다. 수집기가 연결 통로에서 알아내 메시지에 붙여야 하는데(ERD) 그 코드가 아직 없다.
    // Hibernate 가 INET 을 기본으로 못 다루기도 해서, 채우는 코드가 생길 때 columnDefinition 과 함께 넣는다
}

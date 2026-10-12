package com.monimo.api.threaddump

import com.monimo.api.common.web.TimeRange
import com.monimo.api.threaddump.dto.ThreadDumpCursor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

// ClickHouse thread_dumps 한 줄. requestedAtMs = DateTime64(3) 를 epoch ms 로
data class ThreadDumpRow(
    val dumpUuid: UUID,
    val agentKey: String,
    val serviceName: String,
    val requestedBy: String,
    val requestedAtMs: Long,
    val threadCount: Int,
    val dump: String?,
)

// thread_dumps 는 API 서버가 쓰고 읽는 유일한 CH 표다 (ADR #36 · Q16). 적재 처리기를 거치지 않는다
@Repository
class ThreadDumpRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun insert(row: ThreadDumpRow) {
        // 시각은 ms 로 넘기고 CH 쪽에서 DateTime64(3) 로 바꾼다 — JDBC 타입 변환에 기대지 않으려고
        jdbc.update(
            """
            INSERT INTO thread_dumps (agent_id, service_name, dump_uuid, requested_by, requested_at, thread_count, dump)
            VALUES (:agentKey, :serviceName, :dumpUuid, :requestedBy, fromUnixTimestamp64Milli(:requestedAtMs), :threadCount, :dump)
            """.trimIndent(),
            mapOf(
                "agentKey" to row.agentKey,
                "serviceName" to row.serviceName,
                "dumpUuid" to row.dumpUuid.toString(),
                "requestedBy" to row.requestedBy,
                "requestedAtMs" to row.requestedAtMs,
                "threadCount" to row.threadCount,
                "dump" to row.dump,
            ),
        )
    }

    // 최근 요청순. 본문은 안 읽는다. fetch = limit + 1 로 받아 다음 쪽 유무를 안다
    fun findPage(serviceName: String?, agentKey: String?, range: TimeRange?, cursor: ThreadDumpCursor?, fetch: Int): List<ThreadDumpRow> {
        val where = buildList {
            add("1 = 1")
            if (serviceName != null) add("service_name = :serviceName")
            if (agentKey != null) add("agent_id = :agentKey")
            if (range != null) add("requested_at >= fromUnixTimestamp64Milli(:fromMs) AND requested_at < fromUnixTimestamp64Milli(:toMs)")
            if (cursor != null) add("(toUnixTimestamp64Milli(requested_at), dump_uuid) < (:cursorTs, :cursorId)")
        }.joinToString(" AND ")
        val sql = """
            SELECT $SUMMARY_COLUMNS
            FROM thread_dumps
            WHERE $where
            ORDER BY requested_at DESC, dump_uuid DESC
            LIMIT 1 BY dump_uuid
            LIMIT :fetch
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "agentKey" to agentKey,
            "fromMs" to range?.from?.toEpochMilli(),
            "toMs" to range?.to?.toEpochMilli(),
            "cursorTs" to cursor?.ts,
            "cursorId" to cursor?.id,
            "fetch" to fetch,
        )
        return jdbc.query(sql, params) { rs, _ -> summary(rs) }
    }

    fun findOne(dumpUuid: UUID): ThreadDumpRow? =
        jdbc.query(
            "SELECT $SUMMARY_COLUMNS, dump FROM thread_dumps WHERE dump_uuid = :dumpUuid LIMIT 1",
            mapOf("dumpUuid" to dumpUuid.toString()),
        ) { rs, _ -> summary(rs).copy(dump = rs.getString("dump")) }.firstOrNull()

    private fun summary(rs: java.sql.ResultSet) = ThreadDumpRow(
        dumpUuid = UUID.fromString(rs.getString("dump_uuid")),
        agentKey = rs.getString("agent_id"),
        serviceName = rs.getString("service_name"),
        requestedBy = rs.getString("requested_by"),
        requestedAtMs = rs.getLong("requested_at_ms"),
        threadCount = rs.getInt("thread_count"),
        dump = null,
    )

    private companion object {
        const val SUMMARY_COLUMNS =
            "dump_uuid, agent_id, service_name, requested_by, toUnixTimestamp64Milli(requested_at) AS requested_at_ms, thread_count"
    }
}

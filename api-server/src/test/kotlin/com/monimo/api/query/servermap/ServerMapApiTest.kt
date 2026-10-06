package com.monimo.api.query.servermap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// spans 에 줄을 넣으면 MV 가 server_map_1m · service_health_1m 을 채우고, 그걸 GET /server-map 으로 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ServerMapApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@map.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@map.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/server-map?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // 1분 경계에 맞춘 30분 전
    val t0 = Instant.ofEpochSecond((now.epochSecond - 1800) / 60 * 60)
    var seq = 0

    fun span(
        service: String,
        offsetSec: Long,
        kind: String,
        durationMs: Long,
        error: Boolean = false,
        peerService: String = "",
        peerAddress: String = "",
        dbSystem: String? = null,
    ) {
        seq++
        val attributes = dbSystem?.let { "map('db.system', '$it')" } ?: "map()"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind,
                               status_code, http_status, peer_address, peer_service, attributes)
            VALUES ('t$seq', 's$seq', 'p$seq', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), ${durationMs * 1_000_000},
                    '$service', '$service-pod', 'op', '$kind', '${if (error) "ERROR" else "UNSET"}', ${if (error) 500 else 200},
                    '$peerAddress', '$peerService', $attributes)
            """.trimIndent(),
        )
    }

    // 요청을 받은 쪽(SERVER) — 노드
    span("map-gateway", 1, "SERVER", 100)
    span("map-gateway", 2, "SERVER", 100, error = true)
    span("map-order", 3, "SERVER", 80)
    span("map-order", 4, "SERVER", 80, error = true)
    span("map-inventory", 5, "SERVER", 20)
    // 부른 쪽(CLIENT) — 간선. gateway → order 는 두 분에 걸쳐 3번 (40 · 60 · 50ms, 에러 1)
    span("map-gateway", 6, "CLIENT", 40, peerService = "map-order")
    span("map-gateway", 7, "CLIENT", 60, error = true, peerService = "map-order")
    span("map-gateway", 61, "CLIENT", 50, peerService = "map-order")
    span("map-order", 8, "CLIENT", 10, peerAddress = "mysql:3306", dbSystem = "mysql")
    span("map-order", 9, "CLIENT", 300, error = true, peerAddress = "pg.example.com:443")
    span("map-inventory", 10, "CLIENT", 5, peerAddress = "mysql:3306", dbSystem = "mysql")

    val range = "from=$t0&to=${t0.plusSeconds(300)}"

    // 테스트들이 ClickHouse 하나를 같이 써서 다른 테스트의 서비스가 섞일 수 있다. 이 테스트가 넣은 map-* 만 본다
    fun own(data: JsonNode): JsonNode = objectMapper.createObjectNode().apply {
        set<JsonNode>("nodes", objectMapper.valueToTree(data["nodes"].filter { it["service_name"].asText().startsWith("map-") }))
        set<JsonNode>("edges", objectMapper.valueToTree(data["edges"].filter { it["caller_service"].asText().startsWith("map-") }))
    }

    Given("화면이 서버맵을 열 때") {
        When("시간 범위만 주면") {
            val response = get(range)
            val data = own(json(response)["data"])

            Then("요청을 받은 서비스가 이름 순으로 노드가 된다") {
                response.statusCode.value() shouldBe 200
                data["nodes"].map { it["service_name"].asText() } shouldBe listOf("map-gateway", "map-inventory", "map-order")
                val gateway = data["nodes"][0]
                gateway["cnt"].asLong() shouldBe 2
                gateway["err_cnt"].asLong() shouldBe 1
            }

            Then("간선은 부른 쪽 → 불린 쪽 순서이고 callee_kind 로 서비스 · DB · 외부를 가른다") {
                data["edges"].map { "${it["caller_service"].asText()}>${it["callee_service"].asText()}:${it["callee_kind"].asText()}" } shouldBe listOf(
                    "map-gateway>map-order:SERVICE",
                    "map-inventory>mysql:3306:DB",
                    "map-order>mysql:3306:DB",
                    "map-order>pg.example.com:443:EXTERNAL",
                )
            }

            Then("같은 간선의 여러 분을 합치고 평균 소요시간은 ms") {
                val edge = data["edges"][0]
                edge["cnt"].asLong() shouldBe 3
                edge["err_cnt"].asLong() shouldBe 1
                edge["avg_duration_ms"].asDouble() shouldBe 50.0
            }
        }

        When("service_name 을 주면") {
            val data = json(get("$range&service_name=map-order"))["data"]

            Then("그 서비스가 부르거나 불리는 간선과, 거기 나오는 서비스 노드만 온다") {
                data["edges"].map { "${it["caller_service"].asText()}>${it["callee_service"].asText()}" } shouldBe listOf(
                    "map-gateway>map-order",
                    "map-order>mysql:3306",
                    "map-order>pg.example.com:443",
                )
                data["nodes"].map { it["service_name"].asText() } shouldBe listOf("map-gateway", "map-order")
            }
        }

        When("데이터가 없는 구간이면") {
            val data = json(get("from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}"))["data"]

            Then("노드 · 간선 모두 빈 목록") {
                data["nodes"].size() shouldBe 0
                data["edges"].size() shouldBe 0
            }
        }

        When("from 이 to 보다 늦으면") {
            val response = get("from=${t0.plusSeconds(300)}&to=$t0")

            Then("422 UNPROCESSABLE") {
                response.statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(range, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

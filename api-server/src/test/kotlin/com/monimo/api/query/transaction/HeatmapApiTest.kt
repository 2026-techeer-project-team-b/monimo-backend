package com.monimo.api.query.transaction

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

// spans → (MV) transactions → (MV) heatmap_1m 으로 채워진 값을 GET /traces/heatmap 으로 읽는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class HeatmapApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@heatmap.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "hm-order", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@heatmap.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(path: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/traces/$path", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // 5분 경계에 맞춘 30분 전. step 300 칸이 이 시각에서 시작한다
    val t0 = Instant.ofEpochSecond((now.epochSecond - 1800) / 300 * 300)
    var seq = 0

    fun span(offsetSec: Long, durationMs: Long, httpStatus: Int, kind: String = "SERVER") {
        seq++
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO spans (trace_id, span_id, parent_span_id, start_time, duration_ns, service_name, agent_id, span_name, span_kind, status_code, http_status)
            VALUES ('hm-$seq', 's$seq', '', fromUnixTimestamp64Milli(${t0.plusSeconds(offsetSec).toEpochMilli()}, 'UTC'), ${durationMs * 1_000_000},
                    'hm-order', 'pod-a', 'POST /orders', '$kind', '${if (httpStatus >= 500) "ERROR" else "UNSET"}', $httpStatus)
            """.trimIndent(),
        )
    }

    // 첫 1분: 20 · 49ms(구간 0) · 50ms(구간 1) · 실패 120ms(구간 2) · 2600ms(구간 52). 둘째 1분: 30ms(구간 0) · 실패 130ms(구간 2)
    span(1, 20, 200)
    span(2, 49, 200)
    span(3, 50, 200)
    span(4, 120, 500)
    span(5, 2600, 200)
    span(61, 30, 200)
    span(62, 130, 500)
    span(6, 9999, 200, kind = "CLIENT") // 부른 쪽 스팬은 요청이 아니다

    val base = "service_name=hm-order&from=$t0&to=${t0.plusSeconds(300)}"

    fun cells(data: JsonNode) = data["cells"].map {
        "${Instant.parse(it["ts_min"].asText()).epochSecond - t0.epochSecond}|${it["latency_bucket"].asInt()}|${it["is_error"].asBoolean()}|${it["cnt"].asLong()}"
    }

    Given("화면이 히트맵을 그릴 때") {
        When("step 기본값(60)으로 읽으면") {
            val response = get("heatmap?$base")
            val data = json(response)["data"]

            Then("1분 칸 × 50ms 구간 × 성공/실패로 세고, 요청이 없는 칸은 없다") {
                response.statusCode.value() shouldBe 200
                data["bucket_width_ms"].asInt() shouldBe 50
                data["step"].asInt() shouldBe 60
                cells(data) shouldBe listOf(
                    "0|0|false|2",
                    "0|1|false|1",
                    "0|2|true|1",
                    "0|52|false|1",
                    "60|0|false|1",
                    "60|2|true|1",
                )
            }

            Then("is_error 는 true · false") {
                data["cells"][0]["is_error"].isBoolean shouldBe true
            }

            Then("합계가 스캐터의 total_count 와 같다 (빠짐없이 센다)") {
                val total = data["cells"].sumOf { it["cnt"].asLong() }
                total shouldBe json(get("scatter?$base"))["data"]["total_count"].asLong()
                total shouldBe 7
            }
        }

        When("step 300 으로 읽으면") {
            val data = json(get("heatmap?$base&step=300"))["data"]

            Then("5분 칸 하나로 합친다") {
                data["step"].asInt() shouldBe 300
                cells(data) shouldBe listOf("0|0|false|3", "0|1|false|1", "0|2|true|2", "0|52|false|1")
            }
        }

        When("요청이 없는 구간이면") {
            val response = get("heatmap?service_name=hm-order&from=${t0.minusSeconds(3600)}&to=${t0.minusSeconds(1800)}")

            Then("200 에 빈 cells") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["cells"].size() shouldBe 0
            }
        }

        When("step 이 60 의 배수가 아니거나 60 미만이면") {
            Then("400 INVALID_REQUEST") {
                json(get("heatmap?$base&step=90"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
                get("heatmap?$base&step=30").statusCode.value() shouldBe 400
            }
        }

        When("가로 칸이 2000개를 넘으면 (7일을 1분으로)") {
            val response = get("heatmap?service_name=hm-order&from=${t0.minus(Duration.ofDays(7))}&to=$t0")

            Then("422 TIME_RANGE_TOO_WIDE") {
                response.statusCode.value() shouldBe 422
                json(response)["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("heatmap?service_name=hm-unknown&from=$t0&to=${t0.plusSeconds(300)}").statusCode.value() shouldBe 404
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("heatmap?service_name=hm-order&from=${t0.plusSeconds(300)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get("heatmap?$base", bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

package com.monimo.api.query.metric

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
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
import java.time.temporal.ChronoUnit
import java.util.UUID

// metrics_raw 에 값을 넣으면 MV 가 1분 · 1시간 롤업을 채우고, step 에 따라 다른 표를 읽는지 GET /metrics/series 로 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class MetricSeriesApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    passwordEncoder: PasswordEncoder,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "viewer@metric.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))
    applications.save(Application(UUID.randomUUID(), "mt-order", null, null, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    val token = run {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to "viewer@metric.io", "password" to "pw"))
        json(rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java))["data"]["access_token"].asText()
    }

    fun get(query: String, bearer: String? = token): ResponseEntity<String> {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        return rest.exchange("/api/v1/metrics/series?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // 지난 정각. 넣는 값이 전부 한 시간 칸 안에 들어간다
    val t0 = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1))

    fun metric(offsetSec: Long, value: Double, agent: String = "pod-a", hash: Int = 1, type: String = "heap", name: String = "jvm.memory.used") {
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
            VALUES ('mt-order', '$agent', '$name', $hash, map('jvm.memory.type', '$type'), toDateTime(${t0.plusSeconds(offsetSec).epochSecond}, 'UTC'), $value)
            """.trimIndent(),
        )
    }

    // pod-a heap: 첫 1분에 100 · 200 · 300 · 400(15초 간격), 둘째 1분에 500 · 700
    metric(0, 100.0)
    metric(15, 200.0)
    metric(30, 300.0)
    metric(45, 400.0)
    metric(60, 500.0)
    metric(75, 700.0)
    // 같은 파드의 다른 속성 조합, 다른 파드, 다른 지표
    metric(0, 10.0, hash = 2, type = "non_heap")
    metric(0, 1000.0, agent = "pod-b")
    metric(0, 42.0, hash = 3, name = "jvm.thread.count")

    val base = "service_name=mt-order&metric_name=jvm.memory.used&from=$t0&to=${t0.plusSeconds(3600)}"

    fun lines(data: JsonNode) = data["series"].map { "${it["agent_key"].asText()}/${it["attributes"]["jvm.memory.type"].asText()}" }
    fun at(data: JsonNode, line: Int, point: Int): JsonNode = data["series"][line]["points"][point]

    Given("인스펙터가 지표 그래프를 그릴 때") {
        When("step 60 (1분 롤업)이면") {
            val response = get("$base&step=60")
            val data = json(response)["data"]

            Then("metrics_1m 을 읽고 파드 × 속성 조합마다 한 줄이다") {
                response.statusCode.value() shouldBe 200
                data["metric_name"].asText() shouldBe "jvm.memory.used"
                data["source_table"].asText() shouldBe "metrics_1m"
                data["step"].asInt() shouldBe 60
                lines(data) shouldBe listOf("pod-a/heap", "pod-a/non_heap", "pod-b/heap")
            }

            Then("1분마다 평균 · 최소 · 최대 · 마지막 값을 준다") {
                val first = at(data, 0, 0)
                Instant.parse(first["ts_min"].asText()) shouldBe t0
                first["avg_v"].asDouble() shouldBe 250.0
                first["min_v"].asDouble() shouldBe 100.0
                first["max_v"].asDouble() shouldBe 400.0
                first["last_v"].asDouble() shouldBe 400.0
                val second = at(data, 0, 1)
                Instant.parse(second["ts_min"].asText()) shouldBe t0.plusSeconds(60)
                second["avg_v"].asDouble() shouldBe 600.0
                second["last_v"].asDouble() shouldBe 700.0
                data["series"][0]["points"].size() shouldBe 2
            }
        }

        When("step 15 (원본)이면") {
            val data = json(get("service_name=mt-order&metric_name=jvm.memory.used&from=$t0&to=${t0.plusSeconds(120)}&step=15"))["data"]

            Then("metrics_raw 를 읽고 값 하나가 점 하나다") {
                data["source_table"].asText() shouldBe "metrics_raw"
                data["series"][0]["points"].map { it["avg_v"].asDouble() } shouldBe listOf(100.0, 200.0, 300.0, 400.0, 500.0, 700.0)
                at(data, 0, 0)["min_v"].asDouble() shouldBe 100.0
                at(data, 0, 0)["max_v"].asDouble() shouldBe 100.0
            }
        }

        When("step 30 (원본을 30초 칸으로)이면") {
            val data = json(get("service_name=mt-order&metric_name=jvm.memory.used&from=$t0&to=${t0.plusSeconds(120)}&step=30"))["data"]

            Then("칸 안의 값을 묶는다") {
                data["source_table"].asText() shouldBe "metrics_raw"
                at(data, 0, 0)["avg_v"].asDouble() shouldBe 150.0
                at(data, 0, 0)["last_v"].asDouble() shouldBe 200.0
                at(data, 0, 1)["max_v"].asDouble() shouldBe 400.0
            }
        }

        When("step 3600 (1시간 롤업)이면") {
            val data = json(get("$base&step=3600"))["data"]

            Then("metrics_1h 를 읽고 한 시간이 점 하나다") {
                data["source_table"].asText() shouldBe "metrics_1h"
                data["series"][0]["points"].size() shouldBe 1
                val hour = at(data, 0, 0)
                Instant.parse(hour["ts_min"].asText()) shouldBe t0
                hour["avg_v"].asDouble() shouldBe (2200.0 / 6 plusOrMinus 0.001)
                hour["min_v"].asDouble() shouldBe 100.0
                hour["max_v"].asDouble() shouldBe 700.0
                hour["last_v"].asDouble() shouldBe 700.0
            }
        }

        When("step 을 주지 않으면") {
            Then("기간에 맞춰 서버가 고른다 — 1시간이면 60초(1분 롤업)") {
                val data = json(get(base))["data"]
                data["step"].asInt() shouldBe 60
                data["source_table"].asText() shouldBe "metrics_1m"
            }

            Then("30일이면 3시간(1시간 롤업)") {
                val data = json(get("service_name=mt-order&metric_name=jvm.memory.used&from=${t0.minus(Duration.ofDays(30))}&to=$t0"))["data"]
                data["step"].asInt() shouldBe 10800
                data["source_table"].asText() shouldBe "metrics_1h"
            }
        }

        When("agent_key 를 주면") {
            Then("그 파드 줄만 온다") {
                lines(json(get("$base&step=60&agent_key=pod-b"))["data"]) shouldBe listOf("pod-b/heap")
            }
        }

        When("들어온 적 없는 지표면") {
            val response = get("service_name=mt-order&metric_name=no.such.metric&from=$t0&to=${t0.plusSeconds(3600)}")

            Then("404 가 아니라 200 에 빈 series") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["series"].size() shouldBe 0
            }
        }

        When("step 이 틀리면") {
            Then("0 이하는 400") {
                get("$base&step=0").statusCode.value() shouldBe 400
            }

            Then("1분 롤업 구간인데 60 의 배수가 아니면 400") {
                json(get("$base&step=90"))["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }

            Then("1시간 롤업 구간인데 3600 의 배수가 아니면 400") {
                get("$base&step=5400").statusCode.value() shouldBe 400
            }
        }

        When("범위가 너무 넓으면") {
            Then("점이 2000개를 넘으면 422 TIME_RANGE_TOO_WIDE (하루를 15초로)") {
                val response = get("service_name=mt-order&metric_name=jvm.memory.used&from=${t0.minus(Duration.ofDays(1))}&to=$t0&step=15")
                response.statusCode.value() shouldBe 422
                json(response)["error"]["code"].asText() shouldBe "TIME_RANGE_TOO_WIDE"
            }

            Then("원본은 7일까지 — 8일을 원본 step 으로 물으면 422") {
                get("service_name=mt-order&metric_name=jvm.memory.used&from=${t0.minus(Duration.ofDays(8))}&to=$t0&step=30").statusCode.value() shouldBe 422
            }

            Then("1시간 롤업은 365일까지 — 400일은 422, 300일은 된다") {
                get("service_name=mt-order&metric_name=jvm.memory.used&from=${t0.minus(Duration.ofDays(400))}&to=$t0").statusCode.value() shouldBe 422
                get("service_name=mt-order&metric_name=jvm.memory.used&from=${t0.minus(Duration.ofDays(300))}&to=$t0").statusCode.value() shouldBe 200
            }
        }

        When("metric_name 이 빠지면") {
            Then("400 INVALID_REQUEST") {
                get("service_name=mt-order&from=$t0&to=${t0.plusSeconds(3600)}").statusCode.value() shouldBe 400
            }
        }

        When("등록되지 않은 서비스면") {
            Then("404 NOT_FOUND") {
                get("service_name=mt-unknown&metric_name=jvm.memory.used&from=$t0&to=${t0.plusSeconds(3600)}").statusCode.value() shouldBe 404
            }
        }

        When("로그인하지 않았으면") {
            Then("401") {
                get(base, bearer = null).statusCode.value() shouldBe 401
            }
        }
    }
})

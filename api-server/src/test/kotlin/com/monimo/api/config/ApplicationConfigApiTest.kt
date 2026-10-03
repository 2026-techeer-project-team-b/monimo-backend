package com.monimo.api.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// 설정 2개 문을 실제 HTTP 로 돈다. 조회 → 수정(version +1) → 어긋난 version → 범위 밖 → 권한. PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ApplicationConfigApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    passwordEncoder: PasswordEncoder,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "cfg-admin@app.io", passwordEncoder.encode("pw"), "설정관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "cfg-viewer@app.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun call(method: HttpMethod, path: String, bearer: String, body: Any? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body?.let(objectMapper::writeValueAsString), headers), String::class.java)
    }

    fun login(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to "pw"))
        val response = rest.exchange("/api/v1/auth/login", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        return json(response)["data"]["access_token"].asText()
    }

    val admin = login("cfg-admin@app.io")
    val viewer = login("cfg-viewer@app.io")

    val applicationUuid = json(
        call(HttpMethod.POST, "/api/v1/applications", admin, mapOf("name" to "config-target")),
    )["data"]["application_uuid"].asText()
    val path = "/api/v1/applications/$applicationUuid/config"

    fun put(token: String, samplingRate: Any?, expectedVersion: Any?) =
        call(HttpMethod.PUT, path, token, mapOf("sampling_rate" to samplingRate, "expected_version" to expectedVersion))

    Given("설정 조회 (VIEWER+)") {
        When("등록 직후 조회하면") {
            val data = json(call(HttpMethod.GET, path, viewer))["data"]

            Then("등록 때 자동으로 만든 기본값이 나오고 updated_by 는 null 이다") {
                data["service_name"].asText() shouldBe "config-target"
                data["sampling_rate"].decimalValue().toDouble() shouldBe 0.01
                data["version"].asInt() shouldBe 1
                data["updated_by"].isNull shouldBe true
                data["application_config_uuid"].asText().shouldNotBeNull()
            }
        }
    }

    Given("설정 수정 (ADMIN)") {
        When("ADMIN 이 맞는 expected_version 으로 보내면") {
            val response = put(admin, 0.05, 1)
            val data = json(response)["data"]

            Then("200 이고 version 이 1 올라가며 고친 사람이 찍힌다") {
                response.statusCode.value() shouldBe 200
                data["sampling_rate"].decimalValue().toDouble() shouldBe 0.05
                data["version"].asInt() shouldBe 2
                data["updated_by"]["name"].asText() shouldBe "설정관리자"
            }
        }

        When("이미 지나간 expected_version 으로 다시 보내면") {
            val response = put(admin, 0.10, 1)

            Then("409 CONFIG_VERSION_CONFLICT") {
                response.statusCode.value() shouldBe 409
                json(response)["error"]["code"].asText() shouldBe "CONFIG_VERSION_CONFLICT"
            }

            Then("값은 그대로다") {
                json(call(HttpMethod.GET, path, viewer))["data"]["sampling_rate"].decimalValue().toDouble() shouldBe 0.05
            }
        }

        When("sampling_rate 가 0~1 밖이면") {
            val response = put(admin, 1.5, 2)

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }
    }

    Given("권한과 대상") {
        When("VIEWER 가 수정하려 하면") {
            val response = put(viewer, 0.02, 2)

            Then("403 FORBIDDEN") {
                response.statusCode.value() shouldBe 403
                json(response)["error"]["code"].asText() shouldBe "FORBIDDEN"
            }
        }

        When("없는 서비스의 설정을 조회하면") {
            val response = call(HttpMethod.GET, "/api/v1/applications/${UUID.randomUUID()}/config", viewer)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }
})

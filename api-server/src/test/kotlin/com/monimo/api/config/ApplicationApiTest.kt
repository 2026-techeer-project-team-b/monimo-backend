package com.monimo.api.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// 서비스 5개 문을 실제 HTTP 로 돈다. 등록 → 목록(커서) → 상세 → 수정 → 제외. PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class ApplicationApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    applications: ApplicationRepository,
    configs: ApplicationConfigRepository,
    passwordEncoder: PasswordEncoder,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "admin@app.io", passwordEncoder.encode("pw"), "관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "viewer@app.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

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

    val admin = login("admin@app.io")
    val viewer = login("viewer@app.io")

    fun create(token: String, name: String, displayName: String? = null, description: String? = null) =
        call(HttpMethod.POST, "/api/v1/applications", token, mapOf("name" to name, "display_name" to displayName, "description" to description))

    fun listNames(token: String, query: String = "limit=500"): List<String> =
        json(call(HttpMethod.GET, "/api/v1/applications?$query", token))["data"].map { it["name"].asText() }

    Given("서비스 등록 (ADMIN)") {
        When("ADMIN 이 이름 · 표시명 · 설명을 보내면") {
            val response = create(admin, "order-service", "주문 서비스", "주문 생성 · 조회")
            val data = json(response)["data"]

            Then("201 이고 application_uuid 가 나온다. 숫자 id 는 없다") {
                response.statusCode.value() shouldBe 201
                data["application_uuid"].asText().isNotBlank() shouldBe true
                data["name"].asText() shouldBe "order-service"
                data["display_name"].asText() shouldBe "주문 서비스"
                data.has("id") shouldBe false
            }

            Then("application_configs 한 줄이 같이 생긴다 (sampling_rate 0.01 · version 1 · updated_by null)") {
                val app = applications.findByName("order-service").shouldNotBeNull()
                val config = configs.findByApplicationId(app.id!!).shouldNotBeNull()
                config.samplingRate.compareTo(BigDecimal("0.01")) shouldBe 0
                config.version shouldBe 1
                config.updatedBy shouldBe null
            }
        }

        When("같은 이름으로 다시 등록하면") {
            val response = create(admin, "order-service")

            Then("409 APPLICATION_NAME_TAKEN") {
                response.statusCode.value() shouldBe 409
                json(response)["error"]["code"].asText() shouldBe "APPLICATION_NAME_TAKEN"
            }
        }

        When("이름이 빈칸이면") {
            val response = create(admin, "   ")

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("name 필드 자체가 없으면") {
            val response = call(HttpMethod.POST, "/api/v1/applications", admin, mapOf("display_name" to "이름 없음"))

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("VIEWER 가 등록하면") {
            val response = create(viewer, "viewer-service")

            Then("403 FORBIDDEN 이고 만들어지지 않는다") {
                response.statusCode.value() shouldBe 403
                json(response)["error"]["code"].asText() shouldBe "FORBIDDEN"
                applications.findByName("viewer-service") shouldBe null
            }
        }
    }

    Given("서비스 목록 · 상세 (VIEWER+)") {
        listOf("aaa-1", "aaa-2", "aaa-3").forEach { create(admin, it) }

        When("VIEWER 가 limit=2 로 목록을 부르면") {
            val first = json(call(HttpMethod.GET, "/api/v1/applications?limit=2", viewer))
            val nextCursor = first["page"]["next_cursor"].asText()

            Then("name 오름차순 2건과 next_cursor 가 온다") {
                first["data"].map { it["name"].asText() } shouldBe listOf("aaa-1", "aaa-2")
                first["page"]["limit"].asInt() shouldBe 2
                nextCursor.isNotBlank() shouldBe true
            }

            Then("cursor 로 다음 쪽을 부르면 aaa-3 부터 이어진다") {
                val second = listNames(viewer, "limit=2&cursor=$nextCursor")
                second.first() shouldBe "aaa-3"
                second shouldNotContain "aaa-1"
            }
        }

        When("cursor 가 깨진 값이면") {
            val response = call(HttpMethod.GET, "/api/v1/applications?cursor=%%%", viewer)

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
                json(response)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
            }
        }

        When("상세를 부르면") {
            val uuid = applications.findByName("aaa-1")!!.applicationUuid
            val response = call(HttpMethod.GET, "/api/v1/applications/$uuid", viewer)

            Then("서비스 필드와 agent_count 가 온다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["name"].asText() shouldBe "aaa-1"
                json(response)["data"]["agent_count"].asInt() shouldBe 0
            }
        }

        When("없는 UUID 로 상세를 부르면") {
            val response = call(HttpMethod.GET, "/api/v1/applications/${UUID.randomUUID()}", viewer)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }

    Given("서비스 수정 · 제외 (ADMIN)") {
        val uuid = json(create(admin, "edit-service", "고치기 전", "설명"))["data"]["application_uuid"].asText()

        When("display_name 만 보내면") {
            val response = call(HttpMethod.PATCH, "/api/v1/applications/$uuid", admin, mapOf("display_name" to "고친 뒤"))
            val data = json(response)["data"]

            Then("표시명만 바뀌고 설명 · name 은 그대로다") {
                response.statusCode.value() shouldBe 200
                data["display_name"].asText() shouldBe "고친 뒤"
                data["description"].asText() shouldBe "설명"
                data["name"].asText() shouldBe "edit-service"
            }
        }

        When("description 을 빈 문자열로 보내면") {
            val response = call(HttpMethod.PATCH, "/api/v1/applications/$uuid", admin, mapOf("description" to ""))

            Then("설명이 지워진다 (null)") {
                json(response)["data"]["description"].isNull shouldBe true
            }
        }

        When("고칠 값 없이 빈 본문을 보내면") {
            val response = call(HttpMethod.PATCH, "/api/v1/applications/$uuid", admin, emptyMap<String, String>())

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
            }
        }

        When("VIEWER 가 제외하면") {
            Then("403 FORBIDDEN") {
                call(HttpMethod.DELETE, "/api/v1/applications/$uuid", viewer).statusCode.value() shouldBe 403
            }
        }

        When("ADMIN 이 제외하면") {
            val response = call(HttpMethod.DELETE, "/api/v1/applications/$uuid", admin)

            Then("200 DELETED 이고 줄은 남은 채 deleted_at 이 찍힌다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["result"].asText() shouldBe "DELETED"
                applications.findByName("edit-service").shouldNotBeNull().deletedAt.shouldNotBeNull()
            }

            Then("상세 · 재제외는 404, 목록에서 빠진다") {
                call(HttpMethod.GET, "/api/v1/applications/$uuid", viewer).statusCode.value() shouldBe 404
                call(HttpMethod.DELETE, "/api/v1/applications/$uuid", admin).statusCode.value() shouldBe 404
                listNames(viewer) shouldNotContain "edit-service"
                listNames(viewer) shouldContain "order-service"
            }

            Then("제외된 이름으로 다시 등록하면 409 APPLICATION_NAME_TAKEN") {
                val again = create(admin, "edit-service")
                again.statusCode.value() shouldBe 409
                json(again)["error"]["code"].asText() shouldBe "APPLICATION_NAME_TAKEN"
            }
        }
    }
})

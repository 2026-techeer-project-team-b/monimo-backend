package com.monimo.api.alert.channel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.auth.User
import com.monimo.api.auth.UserRepository
import com.monimo.api.auth.UserRole
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

// 채널 5개 문을 실제 HTTP 로 돈다. 등록 → 목록(필터 · 커서) → 상세 → 수정 → 켜고 끄기. PG 는 Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfraConfig::class)
class AlertChannelApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    channels: AlertChannelRepository,
    passwordEncoder: PasswordEncoder,
) : BehaviorSpec({

    val now = Instant.now()
    users.save(User(UUID.randomUUID(), "admin@channel.io", passwordEncoder.encode("pw"), "관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "viewer@channel.io", passwordEncoder.encode("pw"), "보는사람", UserRole.VIEWER, now, now))

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

    val admin = login("admin@channel.io")
    val viewer = login("viewer@channel.io")

    val slackUrl = "https://hooks.slack.com/services/T000/B000/SECRETTOKEN"
    val maskedSlackUrl = "https://hooks.slack.com/services/T000/B000/****"

    fun createSlack(name: String, enabled: Boolean = true) = call(
        HttpMethod.POST, "/api/v1/alert-channels", admin,
        mapOf("name" to name, "type" to "SLACK", "config" to mapOf("webhook_url" to slackUrl, "channel" to "#alert"), "enabled" to enabled),
    )

    fun uuidOf(response: ResponseEntity<String>): String = json(response)["data"]["alert_channel_uuid"].asText()

    Given("채널 등록 (ADMIN)") {
        When("ADMIN 이 SLACK 채널을 등록하면") {
            val response = createSlack("백엔드-알람방")
            val data = json(response)["data"]

            Then("201 이고 config 의 webhook_url 은 가려서 나온다. 숫자 id 는 없다") {
                response.statusCode.value() shouldBe 201
                data["type"].asText() shouldBe "SLACK"
                data["enabled"].asBoolean() shouldBe true
                data["config"]["webhook_url"].asText() shouldBe maskedSlackUrl
                data["config"]["channel"].asText() shouldBe "#alert"
                data.has("id") shouldBe false
                response.body!! shouldNotContain "SECRETTOKEN"
            }

            Then("DB 에는 원래 주소가 저장된다 (알림 서비스가 읽는 값)") {
                val saved = channels.findByAlertChannelUuid(UUID.fromString(data["alert_channel_uuid"].asText())).shouldNotBeNull()
                saved.config["webhook_url"] shouldBe slackUrl
            }
        }

        When("type 이 4종 밖이거나 config 가 틀리면") {
            val badType = call(HttpMethod.POST, "/api/v1/alert-channels", admin, mapOf("name" to "x", "type" to "SMS", "config" to emptyMap<String, Any>()))
            val badConfig = call(
                HttpMethod.POST, "/api/v1/alert-channels", admin,
                mapOf("name" to "x", "type" to "SLACK", "config" to mapOf("webhook_url" to "https://evil.example.com/x")),
            )

            Then("둘 다 400 INVALID_REQUEST") {
                listOf(badType, badConfig).forEach {
                    it.statusCode.value() shouldBe 400
                    json(it)["error"]["code"].asText() shouldBe "INVALID_REQUEST"
                }
            }
        }

        When("VIEWER 가 등록하면") {
            val response = call(
                HttpMethod.POST, "/api/v1/alert-channels", viewer,
                mapOf("name" to "몰래", "type" to "SLACK", "config" to mapOf("webhook_url" to slackUrl)),
            )

            Then("403 FORBIDDEN") {
                response.statusCode.value() shouldBe 403
                json(response)["error"]["code"].asText() shouldBe "FORBIDDEN"
            }
        }
    }

    Given("채널 목록 (VIEWER+)") {
        createSlack("list-1")
        createSlack("list-2", enabled = false)
        createSlack("list-3")

        When("VIEWER 가 limit=2 로 부르면") {
            val first = json(call(HttpMethod.GET, "/api/v1/alert-channels?limit=2", viewer))
            val second = json(call(HttpMethod.GET, "/api/v1/alert-channels?limit=2&cursor=${first["page"]["next_cursor"].asText()}", viewer))

            Then("최신 등록 순으로 오고 config 는 싣지 않는다") {
                first["data"].map { it["name"].asText() } shouldBe listOf("list-3", "list-2")
                first["data"].all { !it.has("config") } shouldBe true
                second["data"][0]["name"].asText() shouldBe "list-1"
            }
        }

        When("enabled=false 로 거르면") {
            val names = json(call(HttpMethod.GET, "/api/v1/alert-channels?enabled=false&limit=500", viewer))["data"].map { it["name"].asText() }

            Then("꺼진 채널만 온다") {
                names shouldBe listOf("list-2")
            }
        }

        When("type 값이 4종 밖이면") {
            val response = call(HttpMethod.GET, "/api/v1/alert-channels?type=SMS", viewer)

            Then("400 INVALID_REQUEST") {
                response.statusCode.value() shouldBe 400
            }
        }
    }

    Given("채널 상세 · 수정 (ADMIN)") {
        val uuid = uuidOf(createSlack("수정용"))

        When("VIEWER 가 상세를 보면") {
            val response = call(HttpMethod.GET, "/api/v1/alert-channels/$uuid", viewer)

            Then("403 — 상세에는 config 가 있어서 ADMIN 만") {
                response.statusCode.value() shouldBe 403
            }
        }

        When("응답으로 받은 가린 주소를 그대로 보내며 이름 · channel 만 바꾸면") {
            val response = call(
                HttpMethod.PUT, "/api/v1/alert-channels/$uuid", admin,
                mapOf("name" to "온콜방", "type" to "SLACK", "config" to mapOf("webhook_url" to maskedSlackUrl, "channel" to "#oncall")),
            )

            Then("200 이고 저장된 주소는 그대로, 이름 · channel 은 바뀐다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["name"].asText() shouldBe "온콜방"
                val saved = channels.findByAlertChannelUuid(UUID.fromString(uuid)).shouldNotBeNull()
                saved.config shouldBe mapOf("webhook_url" to slackUrl, "channel" to "#oncall")
            }
        }

        When("유형을 EMAIL 로 바꾸면") {
            val response = call(
                HttpMethod.PUT, "/api/v1/alert-channels/$uuid", admin,
                mapOf("name" to "온콜-메일", "type" to "EMAIL", "config" to mapOf("to" to listOf("oncall@monimo.io"))),
            )

            Then("config 가 통째로 새 유형의 것으로 바뀐다") {
                response.statusCode.value() shouldBe 200
                val saved = channels.findByAlertChannelUuid(UUID.fromString(uuid)).shouldNotBeNull()
                saved.type shouldBe ChannelType.EMAIL
                saved.config shouldBe mapOf("to" to listOf("oncall@monimo.io"))
            }
        }

        When("없는 UUID 를 부르면") {
            val response = call(HttpMethod.GET, "/api/v1/alert-channels/${UUID.randomUUID()}", admin)

            Then("404 NOT_FOUND") {
                response.statusCode.value() shouldBe 404
                json(response)["error"]["code"].asText() shouldBe "NOT_FOUND"
            }
        }
    }

    Given("채널 켜고 끄기 (ADMIN)") {
        val uuid = uuidOf(createSlack("토글용"))

        When("enabled=false 를 두 번 보내면") {
            val first = json(call(HttpMethod.PATCH, "/api/v1/alert-channels/$uuid/enabled", admin, mapOf("enabled" to false)))["data"]
            val second = json(call(HttpMethod.PATCH, "/api/v1/alert-channels/$uuid/enabled", admin, mapOf("enabled" to false)))["data"]

            Then("둘 다 꺼진 상태이고 두 번째는 updated_at 이 그대로다") {
                first["enabled"].asBoolean() shouldBe false
                second["enabled"].asBoolean() shouldBe false
                second["updated_at"].asText() shouldBe first["updated_at"].asText()
            }
        }

        When("enabled 를 빠뜨리면") {
            val response = call(HttpMethod.PATCH, "/api/v1/alert-channels/$uuid/enabled", admin, emptyMap<String, Any>())

            Then("400 이고 채널 상태는 바뀌지 않는다 (앞에서 끈 그대로)") {
                response.statusCode.value() shouldBe 400
                channels.findByAlertChannelUuid(UUID.fromString(uuid)).shouldNotBeNull().enabled shouldBe false
            }
        }

        When("VIEWER 가 끄려 하면") {
            val response = call(HttpMethod.PATCH, "/api/v1/alert-channels/$uuid/enabled", viewer, mapOf("enabled" to true))

            Then("403 FORBIDDEN") {
                response.statusCode.value() shouldBe 403
            }
        }
    }
})

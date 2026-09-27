package com.monimo.api.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

// 인증 4개 문과 권한 4종(공개 · VIEWER+ · ADMIN · 내부)을 실제 HTTP 로 한 바퀴 돈다. PG 는 Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token"],
)
@ActiveProfiles("auth-test")
@Import(TestInfraConfig::class)
class AuthFlowTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    users: UserRepository,
    refreshTokens: RefreshTokenRepository,
    passwordEncoder: PasswordEncoder,
) : BehaviorSpec({

    val now = Instant.now()
    val admin = users.save(User(UUID.randomUUID(), "admin@test.io", passwordEncoder.encode("admin-pw"), "관리자", UserRole.ADMIN, now, now))
    users.save(User(UUID.randomUUID(), "viewer@test.io", passwordEncoder.encode("viewer-pw"), "보는사람", UserRole.VIEWER, now, now))

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun post(path: String, body: Any? = null, bearer: String? = null, cookie: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            bearer?.let { setBearerAuth(it) }
            cookie?.let { add(HttpHeaders.COOKIE, "${RefreshCookie.NAME}=$it") }
        }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body?.let(objectMapper::writeValueAsString), headers), String::class.java)
    }

    fun get(path: String, bearer: String? = null, extraHeaders: Map<String, String> = emptyMap()): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            bearer?.let { setBearerAuth(it) }
            extraHeaders.forEach { (k, v) -> add(k, v) }
        }
        return rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    // Set-Cookie 머리말에서 refresh 쿠키 원문(속성 제외)을 꺼낸다
    fun refreshCookieOf(response: ResponseEntity<String>): String? =
        response.headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("${RefreshCookie.NAME}=") }

    fun cookieValue(setCookie: String): String = setCookie.substringAfter("=").substringBefore(";")

    fun login(email: String, password: String) = post("/api/v1/auth/login", mapOf("email" to email, "password" to password))

    Given("로그인 문 (공개)") {
        When("맞는 이메일 · 비밀번호를 보내면") {
            val response = login("admin@test.io", "admin-pw")
            val body = json(response)

            Then("200 이고 본문에 access 토큰과 사용자가 온다. refresh 토큰은 본문에 없다") {
                response.statusCode.value() shouldBe 200
                body["data"]["access_token"].asText().isNotBlank() shouldBe true
                body["data"]["expires_in"].asLong() shouldBe 3600
                body["data"]["user"]["email"].asText() shouldBe "admin@test.io"
                body["data"]["user"]["role"].asText() shouldBe "ADMIN"
                body["data"].has("refresh_token") shouldBe false
            }

            Then("refresh 토큰은 httpOnly · SameSite=Strict · Path=/api/v1/auth 쿠키로 온다") {
                val setCookie = refreshCookieOf(response).shouldNotBeNull()
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "SameSite=Strict"
                setCookie shouldContain "Path=${RefreshCookie.PATH}"
                setCookie shouldContain "Max-Age=${Duration.ofDays(14).seconds}"
            }
        }

        When("비밀번호가 틀리면") {
            val response = login("admin@test.io", "wrong")

            Then("401 UNAUTHENTICATED 봉투이고 쿠키를 주지 않는다") {
                response.statusCode.value() shouldBe 401
                json(response)["error"]["code"].asText() shouldBe "UNAUTHENTICATED"
                refreshCookieOf(response) shouldBe null
            }
        }

        When("없는 이메일이면") {
            val response = login("nobody@test.io", "admin-pw")

            Then("같은 401 UNAUTHENTICATED 다 (계정 존재 여부를 구분해 주지 않는다)") {
                response.statusCode.value() shouldBe 401
                json(response)["error"]["code"].asText() shouldBe "UNAUTHENTICATED"
            }
        }
    }

    Given("JWT 로 여는 문 (VIEWER+ · ADMIN)") {
        val adminToken = json(login("admin@test.io", "admin-pw"))["data"]["access_token"].asText()
        val viewerToken = json(login("viewer@test.io", "viewer-pw"))["data"]["access_token"].asText()

        When("토큰 없이 부르면") {
            val response = get("/api/v1/auth/me")

            Then("401 UNAUTHENTICATED 봉투이고 X-Request-Id 가 붙어 있다") {
                response.statusCode.value() shouldBe 401
                json(response)["error"]["code"].asText() shouldBe "UNAUTHENTICATED"
                response.headers.getFirst("X-Request-Id") shouldNotBe null
            }
        }

        When("위조한 토큰으로 부르면") {
            val response = get("/api/v1/auth/me", bearer = "$adminToken-tampered")

            Then("401 UNAUTHENTICATED") {
                response.statusCode.value() shouldBe 401
                json(response)["error"]["code"].asText() shouldBe "UNAUTHENTICATED"
            }
        }

        When("access 토큰으로 me 를 부르면") {
            val response = get("/api/v1/auth/me", bearer = adminToken)

            Then("내 정보가 온다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["user_uuid"].asText() shouldBe admin.userUuid.toString()
                json(response)["data"]["email"].asText() shouldBe "admin@test.io"
            }
        }

        When("VIEWER 가 ADMIN 문을 열면") {
            val response = get("/api/v1/test/admin-only", bearer = viewerToken)

            Then("403 FORBIDDEN 봉투") {
                response.statusCode.value() shouldBe 403
                json(response)["error"]["code"].asText() shouldBe "FORBIDDEN"
            }
        }

        When("ADMIN 이 ADMIN 문을 열면") {
            val response = get("/api/v1/test/admin-only", bearer = adminToken)

            Then("200") {
                response.statusCode.value() shouldBe 200
            }
        }
    }

    Given("재발급 문 (공개, 쿠키 필요)") {
        val first = login("admin@test.io", "admin-pw")
        val firstCookie = cookieValue(refreshCookieOf(first)!!)

        When("쿠키로 재발급하면") {
            val response = post("/api/v1/auth/refresh", cookie = firstCookie)
            val secondCookie = refreshCookieOf(response)?.let(::cookieValue)

            Then("새 access 와 새 refresh 쿠키를 준다 (회전)") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["access_token"].asText().isNotBlank() shouldBe true
                secondCookie.shouldNotBeNull() shouldNotBe firstCookie
            }

            Then("쓴 refresh 로 다시 재발급하면 401 이다") {
                val reuse = post("/api/v1/auth/refresh", cookie = firstCookie)
                reuse.statusCode.value() shouldBe 401
                json(reuse)["error"]["code"].asText() shouldBe "UNAUTHENTICATED"
            }
        }

        When("쿠키 없이 재발급하면") {
            val response = post("/api/v1/auth/refresh")

            Then("401 UNAUTHENTICATED") {
                response.statusCode.value() shouldBe 401
            }
        }

        When("만료된 refresh 로 재발급하면") {
            val expiredRaw = "expired-token"
            refreshTokens.save(RefreshToken(sha256Hex(expiredRaw), admin.id!!, now.minusSeconds(1), now.minusSeconds(100)))
            val response = post("/api/v1/auth/refresh", cookie = expiredRaw)

            Then("401 이고 만료된 줄은 지워진다") {
                response.statusCode.value() shouldBe 401
                refreshTokens.findByTokenHash(sha256Hex(expiredRaw)) shouldBe null
            }
        }
    }

    Given("로그아웃 문 (VIEWER+)") {
        val session = login("admin@test.io", "admin-pw")
        val token = json(session)["data"]["access_token"].asText()
        val cookie = cookieValue(refreshCookieOf(session)!!)

        When("로그아웃하면") {
            val response = post("/api/v1/auth/logout", bearer = token, cookie = cookie)

            Then("200 LOGGED_OUT 이고 쿠키를 지우라고 응답한다") {
                response.statusCode.value() shouldBe 200
                json(response)["data"]["result"].asText() shouldBe "LOGGED_OUT"
                refreshCookieOf(response).shouldNotBeNull() shouldContain "Max-Age=0"
            }

            Then("그 refresh 로는 더 이상 재발급이 안 된다") {
                post("/api/v1/auth/refresh", cookie = cookie).statusCode.value() shouldBe 401
            }
        }
    }

    Given("내부 문 (X-Internal-Token)") {
        val adminToken = json(login("admin@test.io", "admin-pw"))["data"]["access_token"].asText()

        When("머리말 없이 부르면") {
            Then("401 UNAUTHENTICATED") {
                get("/api/v1/internal/test/ping").statusCode.value() shouldBe 401
            }
        }

        When("값이 틀리면") {
            Then("401 UNAUTHENTICATED") {
                get("/api/v1/internal/test/ping", extraHeaders = mapOf("X-Internal-Token" to "nope")).statusCode.value() shouldBe 401
            }
        }

        When("값이 맞으면") {
            Then("200") {
                get("/api/v1/internal/test/ping", extraHeaders = mapOf("X-Internal-Token" to "test-internal-token")).statusCode.value() shouldBe 200
            }
        }

        When("사람 JWT 로 내부 문을 열면") {
            Then("403 FORBIDDEN (화면은 내부 문을 부르지 않는다)") {
                get("/api/v1/internal/test/ping", bearer = adminToken).statusCode.value() shouldBe 403
            }
        }
    }
})

// 권한 검사 시험용 문. auth-test 프로필에서만 뜬다
@Profile("auth-test")
@RestController
class AuthTestController {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/v1/test/admin-only")
    fun adminOnly() = ApiResponse.of(mapOf("ok" to true))

    @GetMapping("/api/v1/internal/test/ping")
    fun ping() = ApiResponse.of(mapOf("pong" to true))
}

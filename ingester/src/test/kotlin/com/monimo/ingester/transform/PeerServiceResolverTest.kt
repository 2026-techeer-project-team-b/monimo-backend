package com.monimo.ingester.transform

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

// 순수 함수라 스프링도 컨테이너도 없이 돈다
class PeerServiceResolverTest : BehaviorSpec({

    fun client(peerAddress: String, peerService: String = "", spanKind: String = "CLIENT") = SpanRow(
        traceId = "a".repeat(32), spanId = "0123456789abcdef", parentSpanId = "",
        startTime = Instant.EPOCH, durationNs = 1, serviceName = "shop-gateway", agentId = "gw-1",
        spanName = "GET /orders", spanKind = spanKind, statusCode = "OK", httpStatus = 200,
        peerAddress = peerAddress, peerService = peerService, attributes = emptyMap(),
    )

    val known = setOf("shop-order", "shop-payment")

    Given("감시 중인 서비스 목록 {shop-order, shop-payment}") {

        When("compose 식 주소 shop-order:8080") {
            Then("포트를 떼고 서비스 이름으로 채운다") {
                PeerServiceResolver.fill(listOf(client("shop-order:8080")), known).single().peerService shouldBe "shop-order"
            }
        }

        When("쿠버네티스 FQDN shop-payment.default.svc.cluster.local:8080") {
            Then("첫 DNS 라벨로 채운다") {
                PeerServiceResolver.fill(listOf(client("shop-payment.default.svc.cluster.local:8080")), known).single().peerService shouldBe "shop-payment"
            }
        }

        When("IP 주소 10.0.0.5:8080") {
            Then("첫 라벨 '10' 은 목록에 없어 그대로 빈 글자 — 서버맵이 EXTERNAL 로 둔다") {
                PeerServiceResolver.fill(listOf(client("10.0.0.5:8080")), known).single().peerService shouldBe ""
            }
        }

        When("localhost:8080 이나 DB 주소 postgres:5432") {
            Then("목록에 없어 비운다 (DB 는 MV 가 db.system 으로 따로 분류한다)") {
                PeerServiceResolver.fill(listOf(client("localhost:8080"), client("postgres:5432")), known).map { it.peerService } shouldBe listOf("", "")
            }
        }

        When("에이전트가 peer.service 를 이미 넣어 줌 (주소와 다른 이름)") {
            Then("그 값이 우선이다. 덮어쓰지 않는다") {
                PeerServiceResolver.fill(listOf(client("shop-order:8080", peerService = "order-api")), known).single().peerService shouldBe "order-api"
            }
        }

        When("호출 대상 주소가 없는 스팬 (INTERNAL 등)") {
            Then("건드리지 않는다") {
                PeerServiceResolver.fill(listOf(client("")), known).single().peerService shouldBe ""
            }
        }

        When("SERVER 스팬에 server.address=shop-order 가 붙어 있음 (자기 주소)") {
            Then("호출하는 쪽(CLIENT · PRODUCER)만 채운다. SERVER 는 자기 이름이 들어가므로 건드리지 않는다") {
                PeerServiceResolver.fill(listOf(client("shop-order:8080", spanKind = "SERVER")), known).single().peerService shouldBe ""
                PeerServiceResolver.fill(listOf(client("shop-order:8080", spanKind = "PRODUCER")), known).single().peerService shouldBe "shop-order"
            }
        }

        When("IPv6 주소 [::1]:8080") {
            Then("라벨이 '[' 가 되어 어떤 이름과도 안 맞는다 — 틀려도 안전한 쪽(빈 글자)") {
                PeerServiceResolver.fill(listOf(client("[::1]:8080")), known).single().peerService shouldBe ""
            }
        }

        When("이름이 비슷하지만 정확히 같지 않음 (shop-orders, Shop-Order)") {
            Then("부분 일치 · 대소문자 무시는 하지 않는다") {
                PeerServiceResolver.fill(listOf(client("shop-orders:8080"), client("Shop-Order:8080")), known).map { it.peerService } shouldBe listOf("", "")
            }
        }
    }

    Given("서비스 목록을 못 받음 (빈 집합)") {
        Then("아무것도 채우지 않고 원본을 그대로 돌려준다") {
            val rows = listOf(client("shop-order:8080"))
            PeerServiceResolver.fill(rows, emptySet()) shouldBe rows
        }
    }

    Given("주소 글자 자르기") {
        Then("포트 → 점 순서로 뗀다") {
            PeerServiceResolver.hostLabel("shop-order:8080") shouldBe "shop-order"
            PeerServiceResolver.hostLabel("shop-order.ns.svc:8080") shouldBe "shop-order"
            PeerServiceResolver.hostLabel("shop-order") shouldBe "shop-order"
        }
    }
})

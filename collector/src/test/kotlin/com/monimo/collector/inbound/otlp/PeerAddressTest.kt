package com.monimo.collector.inbound.otlp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

// gRPC 가 준 소켓 주소를 명부에 넣을 글자로 바꾸는 규칙. 컨테이너 없이 돈다.
//
// 여기서 고정하는 것 둘 :
//   ① 글자 다듬기 : IPv4 를 IPv6 칸에 담은 꼴은 네 토막으로, 영역 꼬리(`%2`)는 뗀다. PG INET 은 앞엣것을
//      조용히 받아 주소 종류를 IPv6 로 기록하고 뒤엣것은 거절한다(실측 2026-10-12). 어느 쪽도 좋지 않다
//   ② 주소라고 할 수 없는 것은 null 이다. null 이면 봉투 메모를 아예 안 붙이고 명부의 주소 칸도 안 건드린다
class PeerAddressTest : BehaviorSpec({

    // 바이트 16개로 IPv6 주소를 직접 만든다. InetAddress.getByAddress 로 만들면 자바가 IPv4 매핑을
    // 먼저 접어 Inet4Address 로 돌려줘서 이 경로를 못 밟는다
    fun ipv6(vararg last: Int, prefix: List<Int>, scope: Int = 0): Inet6Address {
        val bytes = (prefix + last.toList()).map { it.toByte() }.toByteArray()
        return Inet6Address.getByAddress(null, bytes, scope)
    }

    val ipv4MappedPrefix = List(10) { 0 } + listOf(0xFF, 0xFF)

    Given("TCP 연결의 상대 주소") {

        When("IPv4 주소면") {
            Then("그대로 쓴다") {
                peerAddressOf(InetSocketAddress("172.19.0.11", 44321)) shouldBe "172.19.0.11"
            }
        }

        When("IPv4 를 IPv6 칸에 담은 꼴이면") {
            val mapped = ipv6(172, 19, 0, 13, prefix = ipv4MappedPrefix)

            Then("IPv4 네 토막으로 되돌린다") {
                peerAddressOf(InetSocketAddress(mapped, 44322)) shouldBe "172.19.0.13"
            }

            Then("128 이상인 토막도 부호 없이 읽는다") {
                peerAddressOf(InetSocketAddress(ipv6(10, 42, 200, 254, prefix = ipv4MappedPrefix), 1)) shouldBe "10.42.200.254"
            }
        }

        When("그냥 IPv6 주소면") {
            Then("손대지 않는다 : 접을 수 있는 꼴이 아니다") {
                peerAddressOf(InetSocketAddress("::1", 44323)) shouldBe "0:0:0:0:0:0:0:1"
            }
        }

        When("IPv6 주소에 영역 꼬리(zone index)가 붙어 있으면") {
            // fe80::1 에 영역 2. 자바는 hostAddress 를 `fe80:...:1%2` 로 적는다
            val linkLocal = ipv6(0, 0, 0, 1, prefix = listOf(0xFE, 0x80) + List(10) { 0 }, scope = 2)

            Then("꼬리를 뗀다 : PG INET 이 `%2` 가 붙은 글자를 거절한다") {
                linkLocal.hostAddress shouldBe "fe80:0:0:0:0:0:0:1%2" // 자바가 그렇게 적는다는 것을 같이 고정한다
                peerAddressOf(InetSocketAddress(linkLocal, 44324)) shouldBe "fe80:0:0:0:0:0:0:1"
            }
        }
    }

    Given("주소라고 할 수 없는 것") {

        When("TCP 가 아닌 전송 수단이라 주소 타입을 좁힐 수 없으면") {
            // 유닉스 소켓 · 테스트용 인메모리 전송이 이렇다. 주소라고 할 것이 없다
            val notInet = object : SocketAddress() {}

            Then("null 이다") {
                peerAddressOf(notInet) shouldBe null
            }
        }

        When("이름이 안 풀린 주소면") {
            Then("null 이다 : 글자만 있고 주소가 없다") {
                peerAddressOf(InetSocketAddress.createUnresolved("shop-order", 8080)) shouldBe null
            }
        }

        When("아예 값이 없으면 (주소를 꺼내는 상수가 실험 API 라 안 줄 수 있다)") {
            Then("null 이다") {
                peerAddressOf(null) shouldBe null
            }
        }
    }

    Given("주소 글자 만들기를 직접 부를 때") {
        When("보통 IPv4 주소면") {
            Then("자바가 적는 글자와 같다") {
                InetAddress.getByName("10.42.17.64").toAddressText() shouldBe "10.42.17.64"
            }
        }
    }
})

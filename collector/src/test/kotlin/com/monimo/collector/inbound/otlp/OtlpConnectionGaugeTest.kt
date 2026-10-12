package com.monimo.collector.inbound.otlp

import io.grpc.Attributes
import io.grpc.Grpc
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetAddress
import java.net.InetSocketAddress

// 붙어 있는 수 게이지(연결 수 · 에이전트 수)의 더하고 빼는 규칙 (#159). 전송 필터를 손으로 부른다.
// TLS 연결(tls · mtls 칸)은 진짜 악수가 있어야 가릴 수 있어서 스프링 시험(OtlpGrpcMtlsRequireTest · OptionalTest)이 본다
class OtlpConnectionGaugeTest : BehaviorSpec({

    Given("게이지") {
        val registry = SimpleMeterRegistry()
        val gauge = OtlpConnectionGauge(registry)

        When("아무 연결도 없을 때") {
            Then("세 칸 전부 0 으로 미리 등록돼 있다 : 한 번도 안 붙었어도 0 이 보인다") {
                OtlpConnectionGauge.Auth.entries.forEach { gauge.connections(it) shouldBe 0 }
                registry.get(OtlpConnectionGauge.CONNECTIONS).tag("auth", "mtls").gauge().value() shouldBe 0.0
            }
        }

        When("평문 연결이 준비되고(SSL 세션 없음) 끊기면") {
            val ready = gauge.transportReady(Attributes.EMPTY)
            val duringConnection = gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT)
            gauge.transportTerminated(ready)

            Then("plaintext 칸이 1 이었다가 0 으로 돌아온다") {
                duringConnection shouldBe 1
                gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) shouldBe 0
                registry.get(OtlpConnectionGauge.CONNECTIONS).tag("auth", "plaintext").gauge().value() shouldBe 0.0
            }
        }

        When("같은 주소에서 평문 연결 둘이 준비되면") {
            val address = InetSocketAddress(InetAddress.getByName("172.19.0.11"), 51000)
            val withAddress = Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, address).build()
            val first = gauge.transportReady(withAddress)
            val second = gauge.transportReady(withAddress)
            val duringConnection = gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) to gauge.agents(OtlpConnectionGauge.Auth.PLAINTEXT)
            gauge.transportTerminated(first)
            val afterOne = gauge.agents(OtlpConnectionGauge.Auth.PLAINTEXT)
            gauge.transportTerminated(second)

            Then("연결은 2 · 에이전트는 1 이다 : 에이전트 하나가 신호마다 연결을 따로 연다") {
                duringConnection shouldBe (2 to 1)
            }

            Then("하나가 끊겨도 에이전트는 1 이고, 둘 다 끊기면 0 이다") {
                afterOne shouldBe 1
                gauge.agents(OtlpConnectionGauge.Auth.PLAINTEXT) shouldBe 0
                gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) shouldBe 0
                registry.get(OtlpConnectionGauge.AGENTS).tag("auth", "plaintext").gauge().value() shouldBe 0.0
            }
        }

        When("주소를 못 읽는 연결이 준비되면 (인메모리 전송처럼 주소가 없는 경우)") {
            val ready = gauge.transportReady(Attributes.EMPTY)
            val duringConnection = gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) to gauge.agents(OtlpConnectionGauge.Auth.PLAINTEXT)
            gauge.transportTerminated(ready)

            Then("연결 수에는 들어가고 에이전트 수에는 안 들어간다") {
                duringConnection shouldBe (1 to 0)
            }
        }

        When("준비된 적 없는 연결이 끊기면 (악수 중에 떨어진 손님)") {
            gauge.transportTerminated(Attributes.EMPTY)

            Then("아무 칸도 안 움직인다 : 센 적이 없으니 뺄 것도 없다. 거절은 여기서 셀 수 없다는 뜻이다") {
                OtlpConnectionGauge.Auth.entries.forEach { gauge.connections(it) shouldBe 0; gauge.agents(it) shouldBe 0 }
            }
        }
    }
})

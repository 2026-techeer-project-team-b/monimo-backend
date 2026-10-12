package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.OtlpTestClient
import com.monimo.collector.support.TestCerts
import com.monimo.collector.support.TestCertsConfig
import com.monimo.collector.support.TestInfraConfig
import io.grpc.Status
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// 요구 수준 optional (#159, 전환 1단계) : TLS 로 받되 신분증은 있으면 도장을 확인하고 없어도 받는다.
// 이 단계의 쓸모는 "신분증을 빠뜨린 손님이 조용히 멈추지 않는 것" 이다. 게이지의 tls 칸과 mtls 칸을 가르면 누가 아직 안 심었는지 보인다.
//
// 조사(research 5절)가 "끊김 없이 옮길 수 있다" 로 적은 것은 과장이었다 : 평문 손님은 이 단계에서도 못 붙는다.
// 평문 → TLS 전환은 한 번은 반드시 끊긴다 (에이전트 설정을 https 와 기관 인증서로 바꾸고 재시작). 그 사실을 여기서 고정한다
@SpringBootTest(
    properties = [
        "monimo.collector.otlp.grpc.port=0",
        "monimo.collector.otlp.grpc.tls.client-auth=optional",
        "monimo.collector.sampling.ratio=1.0",
        "monimo.collector.health-check.paths=",
    ],
)
@Import(TestInfraConfig::class, TestCertsConfig::class)
class OtlpGrpcMtlsOptionalTest(
    server: OtlpGrpcServer,
    gauge: OtlpConnectionGauge,
    counter: OtlpReceiveCounter,
) : BehaviorSpec({

    val ours = TestCerts.ours

    Given("손님 신분증을 요청하되 없어도 받는 수집기 (optional)") {

        When("신분증 없이 TLS 로만 붙는 손님이 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.TRACES)
            val channel = OtlpTestClient.tls(server.port, ours.ca)
            val rejected = OtlpTestClient.rejectedWith(channel)

            Then("받아진다 : 전환 1단계는 빠뜨린 쪽을 끊지 않는다") {
                rejected shouldBe null
                counter.count(OtlpReceiveCounter.Signal.TRACES) - before shouldBe 1.0
            }

            Then("게이지는 tls 칸에 센다 : 붙었지만 신분증은 안 낸 손님이 몇인지 여기서 본다") {
                gauge.connections(OtlpConnectionGauge.Auth.TLS) shouldBe 1
                gauge.agents(OtlpConnectionGauge.Auth.TLS) shouldBe 1
                gauge.connections(OtlpConnectionGauge.Auth.MTLS) shouldBe 0
                channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
                eventually(5.seconds) { gauge.connections(OtlpConnectionGauge.Auth.TLS) shouldBe 0 }
            }
        }

        When("우리 기관 신분증을 낸 손님이 보내면") {
            val channel = OtlpTestClient.tls(server.port, ours.ca, ours.clientCertificate("shop-gateway"), ours.clientKey("shop-gateway"))
            val rejected = OtlpTestClient.rejectedWith(channel)

            Then("받아지고 게이지는 mtls 칸에 센다 : 넷이 전부 이 칸에 오면 require 로 올려도 된다") {
                rejected shouldBe null
                gauge.connections(OtlpConnectionGauge.Auth.MTLS) shouldBe 1
                channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
                eventually(5.seconds) { gauge.connections(OtlpConnectionGauge.Auth.MTLS) shouldBe 0 }
            }
        }

        When("다른 기관 도장이 찍힌 신분증을 낸 손님이 보내면") {
            val other = TestCerts.other
            val channel = OtlpTestClient.tls(server.port, ours.ca, other.clientCertificate("shop-gateway"), other.clientKey("shop-gateway"))
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("끊긴다 : 없는 것은 봐주지만 틀린 것은 안 봐준다 (grpc ClientAuth.OPTIONAL 의 뜻 그대로)") {
                rejected shouldBe Status.Code.UNAVAILABLE
            }
        }

        When("평문으로 붙는 손님이 보내면") {
            val channel = OtlpTestClient.plaintext(server.port)
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("끊긴다 : optional 은 '신분증 없어도 받음' 이지 '평문도 받음' 이 아니다. 에이전트는 먼저 https 와 기관 인증서로 바꿔야 한다") {
                rejected shouldBe Status.Code.UNAVAILABLE
                gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) shouldBe 0
            }
        }
    }
})

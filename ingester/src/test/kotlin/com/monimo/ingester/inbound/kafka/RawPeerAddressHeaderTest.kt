package com.monimo.ingester.inbound.kafka

import com.monimo.common.kafka.RawHeaders
import com.monimo.common.kafka.RawSignal
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerRecord

// 봉투 메모에서 보낸 쪽 주소를 꺼내는 규칙. 컨테이너 없이 레코드 하나로 돈다.
//
// 메모가 없는 메시지가 실제로 있다 : 이 기능 전에 수집기가 넣은 메시지, 점검 스크립트(telemetrygen)가
// 직접 넣는 메시지가 그렇다. 그때 적재가 깨지지 않고 주소만 빈칸이어야 한다 (ADR `#63`)
class RawPeerAddressHeaderTest : BehaviorSpec({

    fun record(vararg headers: Pair<String, ByteArray>): ConsumerRecord<String, ByteArray> =
        ConsumerRecord(RawSignal.TOPIC, 0, 0L, RawSignal.TRACES.key, ByteArray(0))
            .also { record -> headers.forEach { (name, value) -> record.headers().add(name, value) } }

    Given("raw 토픽에서 꺼낸 메시지") {

        When("주소 메모가 붙어 있으면") {
            Then("UTF-8 글자로 꺼낸다") {
                record(RawHeaders.PEER_ADDRESS to "172.19.0.11".toByteArray()).peerAddress() shouldBe "172.19.0.11"
            }
        }

        When("메모가 아예 없으면") {
            Then("null 이다 : 주소 칸을 건드리지 않는다") {
                record().peerAddress() shouldBe null
            }
        }

        When("다른 메모만 붙어 있으면") {
            Then("null 이다") {
                record(RawErrorHandlerConfig.HEADER_REASON to "poison".toByteArray()).peerAddress() shouldBe null
            }
        }

        When("메모가 있는데 값이 비어 있으면") {
            Then("null 로 본다 : 빈 글자를 주소 칸에 넣으면 INET 이 거절한다") {
                record(RawHeaders.PEER_ADDRESS to ByteArray(0)).peerAddress() shouldBe null
                record(RawHeaders.PEER_ADDRESS to "   ".toByteArray()).peerAddress() shouldBe null
            }
        }

        When("같은 이름 메모가 둘 붙어 있으면") {
            Then("마지막 것을 쓴다 : 재처리로 되돌려 넣는 경로에서 쌓일 수 있다") {
                record(
                    RawHeaders.PEER_ADDRESS to "172.19.0.11".toByteArray(),
                    RawHeaders.PEER_ADDRESS to "172.19.0.99".toByteArray(),
                ).peerAddress() shouldBe "172.19.0.99"
            }
        }
    }
})

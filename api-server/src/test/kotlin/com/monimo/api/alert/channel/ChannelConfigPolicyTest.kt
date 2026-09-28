package com.monimo.api.alert.channel

import com.monimo.api.common.error.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ChannelConfigPolicyTest : BehaviorSpec({

    val slackUrl = "https://hooks.slack.com/services/T000/B000/SECRETTOKEN"

    Given("config 검사") {
        When("SLACK 에 webhook_url 과 channel 이 있으면") {
            val config = ChannelConfigPolicy.validate(ChannelType.SLACK, mapOf("webhook_url" to slackUrl, "channel" to "#alert"))

            Then("그대로 통과한다") {
                config shouldBe mapOf("webhook_url" to slackUrl, "channel" to "#alert")
            }
        }

        When("선택 키가 null 이면") {
            val config = ChannelConfigPolicy.validate(ChannelType.SLACK, mapOf("webhook_url" to slackUrl, "channel" to null))

            Then("저장할 config 에서 빠진다") {
                config shouldBe mapOf("webhook_url" to slackUrl)
            }
        }

        When("잘못된 config 를 보내면") {
            Then("400 으로 거절한다") {
                listOf(
                    ChannelType.SLACK to null,
                    ChannelType.SLACK to mapOf("channel" to "#alert"),
                    ChannelType.SLACK to mapOf("webhook_url" to "https://evil.example.com/x"),
                    ChannelType.SLACK to mapOf("webhook_url" to slackUrl, "token" to "x"),
                    ChannelType.WEBHOOK to mapOf("url" to "http://plain.example.com/hook"),
                    ChannelType.EMAIL to mapOf("to" to emptyList<String>()),
                    ChannelType.EMAIL to mapOf("to" to listOf("not-an-email")),
                    ChannelType.PAGERDUTY to mapOf("routing_key" to ""),
                ).forEach { (type, config) ->
                    shouldThrow<ApiException> { ChannelConfigPolicy.validate(type, config) }
                        .errorCode.status.value() shouldBe 400
                }
            }
        }
    }

    Given("비밀값 가리기") {
        When("유형별로 가리면") {
            val slack = ChannelConfigPolicy.mask(ChannelType.SLACK, mapOf("webhook_url" to slackUrl, "channel" to "#alert"))
            val webhook = ChannelConfigPolicy.mask(ChannelType.WEBHOOK, mapOf("url" to "https://ops.example.com/hooks/abc?token=xyz"))
            val pagerDuty = ChannelConfigPolicy.mask(ChannelType.PAGERDUTY, mapOf("routing_key" to "R0123456789ABCDEF"))
            val email = ChannelConfigPolicy.mask(ChannelType.EMAIL, mapOf("to" to listOf("oncall@monimo.io")))

            Then("Slack 은 마지막 경로만, 웹훅은 호스트 뒤 전부, 키는 끝 4자만 남는다. 이메일은 그대로") {
                slack shouldBe mapOf("webhook_url" to "https://hooks.slack.com/services/T000/B000/****", "channel" to "#alert")
                webhook shouldBe mapOf("url" to "https://ops.example.com/****")
                pagerDuty shouldBe mapOf("routing_key" to "****CDEF")
                email shouldBe mapOf("to" to listOf("oncall@monimo.io"))
            }

            Then("가린 결과에 원래 비밀값이 남지 않는다") {
                slack.toString() shouldNotContain "SECRETTOKEN"
                webhook.toString() shouldNotContain "xyz"
            }
        }
    }

    Given("수정 때 가린 값 되돌리기") {
        val stored = mapOf("webhook_url" to slackUrl, "channel" to "#alert")

        When("응답으로 받은 가린 값을 그대로 보내면") {
            val restored = ChannelConfigPolicy.restoreSecrets(
                ChannelType.SLACK, stored, mapOf("webhook_url" to "https://hooks.slack.com/services/T000/B000/****", "channel" to "#oncall"),
            )

            Then("비밀값은 저장된 값으로, 나머지는 새 값으로") {
                restored shouldBe mapOf("webhook_url" to slackUrl, "channel" to "#oncall")
            }
        }

        When("새 주소를 보내면") {
            val newUrl = "https://hooks.slack.com/services/T000/B000/NEWTOKEN"
            val restored = ChannelConfigPolicy.restoreSecrets(ChannelType.SLACK, stored, mapOf("webhook_url" to newUrl))

            Then("새 주소로 바뀐다") {
                restored shouldBe mapOf("webhook_url" to newUrl)
            }
        }
    }
})

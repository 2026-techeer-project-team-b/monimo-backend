package com.monimo.api.alert.channel.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.DbTime
import com.monimo.api.alert.channel.AlertChannelService
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.security.InternalTokenProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChannelTestResponse(
    val alertChannelUuid: UUID,
    val type: ChannelType,
    val result: String,
    val response: String,
    val testedAt: Instant,
)

// 채널 시험 발송 (API 명세 #13). 저장된 실제 config 를 알림 서비스에 넘겨 한 번 보내 보고, 화면에는 결과만 돌려준다 (비밀값은 싣지 않는다).
// 꺼진 채널도 시험할 수 있다 — 켜기 전에 설정이 맞는지 확인하는 용도. 사건이 없어 notification_history 에는 남기지 않는다
@Service
class ChannelTestService(
    private val channelService: AlertChannelService,
    private val client: ChannelTestClient,
    private val props: NotifierClientProperties,
) {
    // 채널별 마지막 시험 시각. 인스턴스 하나 기준이다 (여러 대로 늘리면 대수만큼 더 보낼 수 있다)
    private val lastTested = ConcurrentHashMap<UUID, Instant>()

    // DB 트랜잭션은 채널을 읽을 때만 잡는다. 외부 호출(최대 10초) 동안 커넥션을 붙잡지 않는다
    fun test(alertChannelUuid: UUID): ChannelTestResponse {
        val channel = channelService.get(alertChannelUuid)
        val reservedAt = reserve(alertChannelUuid)
        val (result, response) = when (val outcome = client.test(channel.type, channel.config)) {
            is ChannelTestOutcome.Tested -> outcome.result to outcome.response
            is ChannelTestOutcome.ChannelUnreachable -> FAILED to "채널 서버가 응답하지 않습니다. ${outcome.reason}".trim()
            is ChannelTestOutcome.NotifierUnavailable -> {
                // 알림 서비스에 닿지 못했으면 밖으로 나간 메시지가 없다. 간격을 돌려줘 바로 다시 시도할 수 있게 한다
                lastTested.remove(alertChannelUuid, reservedAt)
                throw ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "알림 서비스가 응답하지 않습니다.")
            }
        }
        return ChannelTestResponse(channel.alertChannelUuid, channel.type, result, response, DbTime.now())
    }

    // 간격 안에 다시 누르면 429. 시각을 먼저 잡아 두므로 동시에 두 번 눌러도 한 번만 나간다
    private fun reserve(alertChannelUuid: UUID): Instant {
        val now = Instant.now()
        var allowed = false
        lastTested.compute(alertChannelUuid) { _, last ->
            if (last == null || !now.isBefore(last.plus(props.testCooldown))) {
                allowed = true
                now
            } else {
                last
            }
        }
        if (!allowed) {
            throw ApiException(ErrorCode.TOO_MANY_REQUESTS, "같은 채널은 ${props.testCooldown.seconds}초에 한 번만 시험할 수 있습니다.")
        }
        return now
    }

    private companion object {
        const val FAILED = "FAILED"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifierClientProperties::class)
class ChannelTestConfig {
    @Bean
    fun channelTestClient(props: NotifierClientProperties, token: InternalTokenProperties, objectMapper: ObjectMapper): ChannelTestClient =
        HttpChannelTestClient(props, token.internalToken, objectMapper)
}

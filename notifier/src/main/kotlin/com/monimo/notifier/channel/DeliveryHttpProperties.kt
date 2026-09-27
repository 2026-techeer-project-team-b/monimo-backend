package com.monimo.notifier.channel

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("monimo.notifier.http")
data class DeliveryHttpProperties(
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val requestTimeout: Duration = Duration.ofSeconds(5),
)

package com.monimo.notifier.channel

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(DeliveryHttpProperties::class)
class ChannelConfig

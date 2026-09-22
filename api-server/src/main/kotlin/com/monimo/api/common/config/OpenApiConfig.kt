package com.monimo.api.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// Swagger UI 첫 화면에 보이는 제목 · 설명. 명세 자체는 컨트롤러 코드에서 자동으로 뽑힌다.
// 노션 「API 명세」가 설계 정본이고, 여기서 나온 명세는 구현이 그와 맞는지 대조하는 용도다.
@Configuration
@ConditionalOnProperty("springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfig {

    @Bean
    fun monimoOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("모니모니터링 API 서버")
            .version("v1")
            .description("화면이 쓰는 REST API. 설계 정본은 노션 「API 명세」이고, 이 문서는 구현과 대조하는 용도다."),
    )
}

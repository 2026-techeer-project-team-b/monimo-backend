package com.monimo.api.config

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.config.dto.ApplicationConfigResponse
import com.monimo.api.config.dto.UpdateApplicationConfigRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 설정 2개 문 (API 명세 10 · 11번: 조회 VIEWER+, 수정 ADMIN)
@RestController
@RequestMapping("/api/v1/applications/{applicationUuid}/config")
class ApplicationConfigController(
    private val applicationConfigService: ApplicationConfigService,
) {

    @GetMapping
    fun get(@PathVariable applicationUuid: UUID): ApiResponse<ApplicationConfigResponse> =
        ApiResponse.of(applicationConfigService.get(applicationUuid))

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    fun update(
        @PathVariable applicationUuid: UUID,
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateApplicationConfigRequest,
    ): ApiResponse<ApplicationConfigResponse> =
        ApiResponse.of(applicationConfigService.update(applicationUuid, UUID.fromString(jwt.subject), request))
}

package com.monimo.api.config

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.config.dto.ApplicationDeletedResponse
import com.monimo.api.config.dto.ApplicationDetailResponse
import com.monimo.api.config.dto.ApplicationResponse
import com.monimo.api.config.dto.CreateApplicationRequest
import com.monimo.api.config.dto.UpdateApplicationRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 서비스 5개 문 (API 명세 §0-2: 목록 · 상세 VIEWER+, 등록 · 수정 · 제외 ADMIN)
@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController(
    private val applicationService: ApplicationService,
) {

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateApplicationRequest): ApiResponse<ApplicationResponse> =
        ApiResponse.of(ApplicationResponse.from(applicationService.create(request)))

    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<ApplicationResponse>> = applicationService.list(cursor, PageLimit.of(limit))

    @GetMapping("/{applicationUuid}")
    fun get(@PathVariable applicationUuid: UUID): ApiResponse<ApplicationDetailResponse> =
        ApiResponse.of(applicationService.get(applicationUuid))

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{applicationUuid}")
    fun update(
        @PathVariable applicationUuid: UUID,
        @RequestBody request: UpdateApplicationRequest,
    ): ApiResponse<ApplicationResponse> =
        ApiResponse.of(ApplicationResponse.from(applicationService.update(applicationUuid, request)))

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{applicationUuid}")
    fun delete(@PathVariable applicationUuid: UUID): ApiResponse<ApplicationDeletedResponse> =
        ApiResponse.of(ApplicationDeletedResponse(applicationService.delete(applicationUuid).applicationUuid))
}

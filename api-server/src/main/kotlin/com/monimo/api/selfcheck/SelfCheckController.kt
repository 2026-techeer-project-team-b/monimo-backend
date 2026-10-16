package com.monimo.api.selfcheck

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.selfcheck.dto.SelfCheckResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 내부 문 (X-Internal-Token). 파수꾼이 카나리 뒤에 한 번 더 부른다 (monimo-watchdog #20)
@RestController
@RequestMapping("/api/v1/internal/self-check")
class SelfCheckController(private val selfCheckService: SelfCheckService) {

    @GetMapping
    fun check(): ApiResponse<SelfCheckResponse> = ApiResponse.of(selfCheckService.check())
}

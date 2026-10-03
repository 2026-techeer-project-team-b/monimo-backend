package com.monimo.api.query.servermap

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.servermap.dto.ServerMapResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 서버맵 (API 명세 #41 · FN-21 · FN-22, VIEWER+)
@RestController
@RequestMapping("/api/v1/server-map")
class ServerMapController(
    private val serverMapService: ServerMapService,
) {

    @GetMapping
    fun get(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ApiResponse<ServerMapResponse> =
        ApiResponse.of(serverMapService.get(serviceName, TimeRange.of(from, to)))
}

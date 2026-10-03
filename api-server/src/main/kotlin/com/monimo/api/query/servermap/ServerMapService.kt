package com.monimo.api.query.servermap

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.servermap.dto.ServerMapResponse
import org.springframework.stereotype.Service

// service_name 을 주면 그 서비스가 부르거나 불리는 간선만, 노드는 그 간선에 나오는 서비스만 남긴다
@Service
class ServerMapService(
    private val serverMapRepository: ServerMapRepository,
) {

    fun get(serviceName: String?, range: TimeRange): ServerMapResponse {
        val edges = serverMapRepository.findEdges(serviceName, range)
        val nodes = serverMapRepository.findNodes(range)
        if (serviceName == null) return ServerMapResponse(nodes, edges)

        val connected = edges.flatMap { listOf(it.callerService, it.calleeService) }.toSet() + serviceName
        return ServerMapResponse(nodes.filter { it.serviceName in connected }, edges)
    }
}

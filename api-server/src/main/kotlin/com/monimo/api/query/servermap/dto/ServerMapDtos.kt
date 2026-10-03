package com.monimo.api.query.servermap.dto

// 서버맵 (API 명세 #41). 노드는 요청을 받은 우리 서비스만, DB · 외부 노드는 화면이 간선의 callee_kind 로 만든다
data class ServerMapResponse(
    val nodes: List<ServerMapNode>,
    val edges: List<ServerMapEdge>,
)

data class ServerMapNode(
    val serviceName: String,
    val cnt: Long,
    val errCnt: Long,
)

data class ServerMapEdge(
    val callerService: String,
    val calleeService: String,
    val calleeKind: String, // SERVICE · DB · EXTERNAL
    val cnt: Long,
    val errCnt: Long,
    val avgDurationMs: Double,
)

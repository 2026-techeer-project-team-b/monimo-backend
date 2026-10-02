package com.monimo.ingester.transform

// 비어 있는 peerService 를 호출 대상 주소에서 채운다. 스프링도 PG 도 모르는 순수 코드다.
//
// OTel Java 에이전트 2.x 는 CLIENT 스팬에 server.address · server.port 만 넣고 peer.service 는 넣지 않는다.
// 서버맵 MV(mv_server_map_1m)는 insert 시점에 peer_service 가 비어 있으면 주소를 노드 이름으로 쓰고
// EXTERNAL 로 분류해 버리므로, spans 에 넣기 전에 여기서 채워야 한다. 조회가 나중에 고칠 수 없다.
//
// 규칙: 주소의 첫 DNS 라벨(포트 제거)이 감시 중인 서비스 이름과 정확히 같을 때만 채운다.
//   shop-order:8080                      → shop-order
//   shop-order.default.svc.cluster.local → shop-order
//   10.0.0.5:8080 · localhost:8080       → 불일치 → 그대로 빈 글자 (서버맵이 EXTERNAL 로 둔다)
// 쿠버네티스 · compose 모두 서비스 DNS 이름이 서비스 이름과 같아서 이 규칙으로 충분하다.
// 부분 일치 · 별칭 · 대소문자 무시는 하지 않는다 — 틀리게 맞추면 가짜 간선이 생기고, 안 맞추면 눈에 띈다
object PeerServiceResolver {

    // 호출하는 쪽 스팬만 대상이다. SERVER 스팬의 server.address 는 자기 자신의 주소라 채우면 자기 이름이 들어간다
    private val CALLER_KINDS = setOf("CLIENT", "PRODUCER")

    // known 에 있는 이름으로만 채운다. 에이전트가 peer.service 를 이미 넣어 줬으면 그 값이 우선이다
    fun fill(rows: List<SpanRow>, known: Set<String>): List<SpanRow> {
        if (known.isEmpty()) return rows // 목록을 못 받았으면(PG 첫 로드 실패 등) 아무것도 채우지 않는다
        return rows.map { row ->
            if (row.spanKind !in CALLER_KINDS || row.peerService.isNotEmpty() || row.peerAddress.isEmpty()) return@map row
            val candidate = hostLabel(row.peerAddress)
            if (candidate in known) row.copy(peerService = candidate) else row
        }
    }

    // "shop-order.default.svc.cluster.local:8080" → "shop-order". 포트를 떼고 첫 점 앞까지.
    // IPv6([::1]:8080)는 "[" 가 나와 어떤 서비스 이름과도 안 맞으므로 비워진다 — 틀려도 안전한 쪽
    internal fun hostLabel(address: String): String =
        address.substringBefore(':').substringBefore('.')
}

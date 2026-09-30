package com.monimo.ingester.transform

// 스팬을 저장하는 곳. **무엇으로 저장하는지는 모른다.**
//
// 이 파일이 outbound/clickhouse 가 아니라 여기(도메인 쪽)에 있는 이유:
// "스팬을 저장한다" 는 것은 우리 규칙이고, "ClickHouse 로 저장한다" 는 것은 그 규칙을 지키는 한 가지 방법일 뿐이다.
// 규칙을 도메인이 들고 있고 구현이 그것을 따르게 하면(의존 역전), 저장소를 바꿔도 변환 코드는 그대로다.
// 테스트에서도 가짜 구현을 끼워 컨테이너 없이 돌릴 수 있다.
fun interface SpanStore {

    // 한 번에 여러 줄을 넣는다. 한 줄씩 넣으면 ClickHouse 가 작은 조각을 너무 많이 만들어 느려진다
    fun save(rows: List<SpanRow>)
}

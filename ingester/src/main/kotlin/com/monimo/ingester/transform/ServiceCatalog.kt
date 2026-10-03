package com.monimo.ingester.transform

// 감시 중인 서비스 이름 목록을 주는 곳. 어디서 가져오는지는 모른다 (포트).
//
// 적재 처리기가 PG applications 표를 읽는 유일한 이유는 "이 주소가 우리 서비스인가" 를 알기 위해서다.
// 표 주인은 API 서버지만 읽기만 한다 — 수집기가 샘플링 비율을 읽는 것과 같은 성격 (ADR #20)
fun interface ServiceCatalog {

    // 화면에서 등록했고 제외(deleted_at)되지 않은 서비스 이름. 구현이 캐시하므로 자주 불러도 된다
    fun activeNames(): Set<String>
}

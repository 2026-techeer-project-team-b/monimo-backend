package com.monimo.collector.sampling

// 지금 쓸 샘플링 비율을 주는 곳. 어디서 가져오는지는 모른다 (포트).
//
// 비율의 정본은 PG `application_configs` 이고 수집기는 읽기만 한다 (ADR #20 · #33 · #36).
// 구현이 캐시하므로 요청마다 불러도 된다.
fun interface SamplingRateSource {

    fun rates(): SamplingRates
}

// 한 번 읽어 온 비율 한 벌. 불변이다.
//
// 불변이어야 하는 이유: 읽기는 요청마다(초당 수천 번) 일어나고 쓰기는 30초에 한 번이라
// 잠금 대신 참조 하나를 통째로 갈아끼운다. 그래서 읽는 쪽은 "옛 벌" 또는 "새 벌" 중
// 완성된 하나를 보는데, 안을 꺼내 고치면 반쯤 바뀐 상태가 보인다.
class SamplingRates private constructor(
    // 서비스별 원본 값. 지금은 applied 를 만드는 재료이고 등록 여부를 보는 데 쓴다.
    // 서비스별로 다른 비율을 쓰게 되면(ADR #53 되돌림 ①) 이 칸을 직접 읽는다
    val byService: Map<String, Double>,
    // 실제로 쓰는 값. byService 의 최댓값이고 비어 있으면 기본값이다.
    // 최댓값인 이유: 선이 하나면 한 트레이스가 전부 남거나 전부 버려져서 쪼개지지 않는다 (ADR #53)
    val applied: Double,
    // PG 를 한 번이라도 읽는 데 성공했나. byService 가 비었는지로는 이것을 알 수 없다:
    // "못 읽었다" 와 "읽었는데 0줄이다"(seed 를 안 돌렸다 · 전부 deleted_at · 엉뚱한 DB)가
    // 둘 다 빈 Map 이라서 구분이 안 되고, 뒤쪽은 운영에서 조용히 기본값으로 도는 사고다
    val loaded: Boolean,
) {

    fun registered(serviceName: String): Boolean = byService.containsKey(serviceName)

    companion object {

        // PG 를 아직 못 읽은 상태. 기본값으로 돌면서 "읽은 적 없다" 를 들고 있는다
        fun unread(fallback: Double): SamplingRates = SamplingRates(emptyMap(), fallback, loaded = false)

        // byService 를 복사해 둔다. 넘긴 Map 을 호출자가 나중에 고쳐도 이 벌은 안 바뀐다.
        // applied 는 복사본으로 계산한다: 원본으로 계산하면 호출자가 그 사이 고칠 때 둘이 어긋난다
        fun of(byService: Map<String, Double>, fallback: Double): SamplingRates {
            val copied = byService.toMap()
            return SamplingRates(copied, copied.values.maxOrNull() ?: fallback, loaded = true)
        }
    }
}

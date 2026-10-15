package com.monimo.collector.sampling

// 지금 쓸 샘플링 비율을 주는 곳. 어디서 가져오는지는 모른다 (포트).
//
// 비율의 정본은 PG `application_configs` 이고 수집기는 읽기만 한다 (ADR #20 · #33 · #36).
// 구현이 캐시하므로 요청마다 불러도 된다.
fun interface SamplingRateSource {

    fun rates(): SamplingRates
}

// 서비스 한 줄. 어느 묶음(system)에 속하고 비율이 얼마인가.
//
// 묶음은 "서로 부르는 서비스들" 의 이름이다 (applications.system, #205). 쇼핑몰 넷은 shop,
// 우리 모듈 다섯은 monimo 다. 스팬에는 묶음이 안 적혀 오므로 서비스 이름으로 찾는다
data class ServiceRate(val system: String, val ratio: Double)

// 한 번 읽어 온 비율 한 벌. 불변이다.
//
// 불변이어야 하는 이유: 읽기는 요청마다(초당 수천 번) 일어나고 쓰기는 30초에 한 번이라
// 잠금 대신 참조 하나를 통째로 갈아끼운다. 그래서 읽는 쪽은 "옛 벌" 또는 "새 벌" 중
// 완성된 하나를 보는데, 안을 꺼내 고치면 반쯤 바뀐 상태가 보인다.
class SamplingRates private constructor(
    // 서비스별 원본 줄. 등록 여부를 보고 로그에 찍는 데 쓴다
    val byService: Map<String, ServiceRate>,
    // 실제로 쓰는 선. 묶음마다 하나이고 그 묶음 줄들의 최댓값이다 (ADR #72 · `#53` 보완).
    //
    // 묶음 안에서 최댓값인 이유: 한 요청은 같은 묶음의 서비스들만 지나므로, 그 안에서 선이 갈리면
    // 같은 trace 의 위아래가 다른 선으로 재어져 고아 스팬이 생긴다 (#121 실측 9%).
    // 묶음끼리는 같은 trace_id 를 공유하지 않는다(#206 조사 5절 실측, 603 trace 중 0건)
    // 그래서 선이 달라도 쪼개지지 않는다. 쇼핑몰 1% · 모니모 50% 가 이래서 같이 설 수 있다
    val bySystem: Map<String, Double>,
    // 묶음을 모를 때 쓰는 선. 미등록 서비스와 "PG 를 아직 못 읽음" 이 이 값을 쓴다.
    //
    // 전체 최댓값을 주지 않는 이유: 미등록은 "어느 묶음인지 모른다" 는 뜻이라 특정 묶음의 값을
    // 빌려 올 근거가 없고, 가장 큰 값을 주면 남의 앱이 모니모 50% 를 타고 들어온다 (ADR #72)
    val fallback: Double,
    // PG 를 한 번이라도 읽는 데 성공했나. byService 가 비었는지로는 이것을 알 수 없다:
    // "못 읽었다" 와 "읽었는데 0줄이다"(seed 를 안 돌렸다 · 전부 deleted_at · 엉뚱한 DB)가
    // 둘 다 빈 Map 이라서 구분이 안 되고, 뒤쪽은 운영에서 조용히 기본값으로 도는 사고다
    val loaded: Boolean,
) {

    fun registered(serviceName: String): Boolean = byService.containsKey(serviceName)

    // 이 서비스의 스팬에 댈 선. 모르는 이름이면 기본 선이다
    fun appliedFor(serviceName: String): Double {
        val system = byService[serviceName]?.system ?: return fallback
        return bySystem[system] ?: fallback
    }

    // 어느 선에서도 버려지지 않는 상태인가. 요청을 다시 만들지 않는 지름길의 조건이다.
    // 미등록 서비스가 섞여 올 수 있으므로 기본 선까지 같이 본다 (로컬 · 테스트가 이 길로 간다)
    val passesEverything: Boolean = fallback >= 1.0 && bySystem.values.all { it >= 1.0 }

    companion object {

        // PG 를 아직 못 읽은 상태. 기본값으로 돌면서 "읽은 적 없다" 를 들고 있는다
        fun unread(fallback: Double): SamplingRates =
            SamplingRates(emptyMap(), emptyMap(), fallback, loaded = false)

        // byService 를 복사해 둔다. 넘긴 Map 을 호출자가 나중에 고쳐도 이 벌은 안 바뀐다.
        // 묶음별 선도 복사본으로 계산한다: 원본으로 계산하면 호출자가 그 사이 고칠 때 둘이 어긋난다
        fun of(byService: Map<String, ServiceRate>, fallback: Double): SamplingRates {
            val copied = byService.toMap()
            val bySystem = copied.values
                .groupingBy { it.system }
                .fold(Double.NEGATIVE_INFINITY) { max, row -> maxOf(max, row.ratio) }
            return SamplingRates(copied, bySystem, fallback, loaded = true)
        }
    }
}

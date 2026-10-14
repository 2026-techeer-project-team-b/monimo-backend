package com.monimo.ingester.job.dlq // 잡(job) : 한 번 돌고 끝나는 일. 들어오는 문(inbound)도 나가는 문(outbound)도 아니라 자리를 따로 둔다

import com.monimo.common.kafka.RawSignal
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// raw.dlq 에 쌓인 것을 raw 로 되넣는 잡의 설정 (ADR 참조 · #195).
//
// 이 잡은 **사람이 부른다** : docker compose run --rm dlq-reprocess.
// 주기 실행을 넣지 않은 이유는 "돌릴 이유가 생긴 횟수가 아직 0번" 이기 때문이다
// (ADR #55 · #66 ① · #67 기각 ⑥ 에서 팀이 세 번 쓴 기준). 두 번 부르게 되면 주기 실행으로 올린다.
@ConfigurationProperties("monimo.ingester.dlq-reprocess")
data class DlqReprocessProperties(
    // 읽는 토픽과 되넣는 토픽. 기본값을 글자로 두지 않고 약속 파일에서 가져온다 (common/kafka/RawSignal.kt)
    val sourceTopic: String = "raw.dlq",
    val targetTopic: String = RawSignal.TOPIC,

    // **ingester 가 아니어야 한다.** 같은 이름을 쓰면 잡이 뜰 때와 끝날 때 각각 그룹 리밸런스가 나서
    // 적재 처리기의 raw 소비가 멈춘다 (실측 211밀리초 · 229밀리초). 파티션이 섞이지는 않는다 :
    // 할당은 구독한 토픽만 따라간다. 그래도 멈추는 것은 공짜가 아니라 별 이름을 쓴다
    val groupId: String = "dlq-reprocess",

    // x-dlq-attempt 가 이 수에 닿으면 되넣지 않고 **그 자리에서 멈춘다**(커밋도 안 한다).
    // 한 번 돌리는 동안에는 고리가 안 생기지만(실패분은 토픽 끝에 새 오프셋으로 붙는다)
    // 회차를 넘기면 왕복한다 : 하루에 한 번 돌리면 하루에 한 바퀴씩 영원히 돈다.
    // 3 은 ADR #51 결정 프롬프트가 적어 둔 "보통 3번에서 5번" 의 아래쪽이다
    val maxAttempt: Int = 3,

    // poll 한 번에 기다리는 시간. 빈 poll 이 emptyPolls 번 연속이면 다 읽은 것으로 보고 끝낸다.
    // 첫 poll 은 그룹에 들어가는 중이라 비어 있을 수 있어서 한 번으로는 못 끊는다
    val pollTimeout: Duration = Duration.ofSeconds(5),
    val emptyPolls: Int = 2,

    // 한 회차에 다루는 상한. 사람이 부르는 잡이라 끝이 보이게 둔다
    val maxRecords: Int = 10_000,

    // 되넣지 않고 무엇을 할지만 찍어 본다. 처음 돌릴 때 이걸로 한 번 보고 나서 진짜로 돌린다
    val dryRun: Boolean = false,
)

-- 커밋 전에 죽은 적재 처리기가 같은 레코드를 다시 넣을 때 중복을 걸러 낸다 (#187, ADR 참조).
--
-- 문제 : 적재 처리기가 ClickHouse 에 넣은 뒤 Kafka 오프셋을 커밋하기 전에 죽으면, 다시 뜰 때
-- 커밋된 자리부터 다시 읽어 같은 레코드를 또 넣는다. Kafka 소비자의 정상 성질("최소 한 번")이고
-- 고칠 자리는 받는 쪽이다. 원본은 (trace_id, span_id) 로 가려낼 수 있지만 집계 표는 합쳐진 숫자라
-- 가려낼 수 없다 : 같은 1분의 에러율이 실제 10% 인데 집계가 18.18% 로 답했다(실측 4회,
-- docs/kafka-reprocess/10-baseline.md).
--
-- 장치 : ClickHouse 가 "최근에 들어온 묶음 N 개의 지문" 을 기억하고 같은 지문이 다시 오면 버린다.
-- 그 기억의 크기가 non_replicated_deduplication_window 이고 **기본값이 0 이라 꺼져 있었다.**
-- 복제 표 쪽은 replicated_deduplication_window 가 10000 으로 켜져 있는데 비복제 표만 0 이다(실측).
-- 이어 주는 스위치(deduplicate_blocks_in_dependent_materialized_views)는 이미 1 이라,
-- 기억만 켜면 MV 쪽으로도 판정이 이어진다.
--
-- **단위는 시간이 아니라 묶음 개수다.** 100 은 "최근 100 묶음을 기억한다" 는 뜻이다.
--
-- 왜 표 열 군데인가 : 적재 처리기가 직접 넣는 표는 셋(spans · metrics_raw · logs)인데, 줄이 들어오는
-- 순간 MV 일곱이 자기 표에 옮겨 적는다. **중복 제거 기억은 표마다 따로 있어야 한다.**
-- 원본에만 켜면 원본은 걸러지는데 집계는 그대로 두 번 세서 원본과 집계가 오히려 어긋난다
-- (예비 행렬 B : 원본 10 · 집계 20, docs/kafka-reprocess/20-stage2-plan.md).
--   spans       -> transactions -> heatmap_1m · url_stats_1m · server_map_1m · service_health_1m
--   metrics_raw -> metrics_1m -> metrics_1h
--   logs        (MV 없음. 그래도 원본이라 중복된다)
-- transactions -> heatmap_1m 과 metrics_1m -> metrics_1h 는 **연쇄 2단**이다.
--
-- thread_dumps 는 넣지 않는다 : API 서버가 넣는 표라 이 경로와 무관하다.
--
-- 왜 100 인가 : 적재 처리기가 지금 1대라 죽어 있는 동안 넣는 쪽이 아무도 없어 기억이 거의 밀리지 않는다.
-- **raw 가 파티션 3개라 쿠버네티스에서 최대 3대까지 뜰 수 있고, 2대 이상이 되면 나머지 대들이 계속
-- 넣어 기억이 밀린다. 그때 이 값을 다시 본다**(ADR 되돌림 ②).
--
-- 표를 다시 만들지 않아도 되는 근거 : MODIFY SETTING 은 설정을 바꾸기 전에 쓰인 조각에도 먹는다
-- (#119 리뷰에서 확인했다. ClickHouse 가 조각이 아니라 표 설정을 본다). 그래서 ALTER 하나로 끝난다.
--
-- 이 장치가 막는 것과 못 막는 것 (docs/seungjo/187-aggregate-duplication/research.md 2.5) :
--   막는다   같은 오프셋 재처리 (커밋 전 크래시 · 리밸런싱)
--   못 막는다 같은 스팬이 새 오프셋으로 다시 온 경우 (에이전트 재전송 · 수집기 재시도)
--   못 막는다 기억에서 밀려 나간 뒤의 재처리 (raw.dlq 되넣기 · 오프셋 되감기 · 보관 기간 재생)
-- 뒤 둘의 해법은 원본에서 그 구간을 다시 집계하는 것 하나뿐이고, 되넣는 잡을 만들 때 같이 정한다.
ALTER TABLE monimo.spans             MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.metrics_raw       MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.logs              MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.transactions      MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.heatmap_1m        MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.url_stats_1m      MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.server_map_1m     MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.service_health_1m MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.metrics_1m        MODIFY SETTING non_replicated_deduplication_window = 100;
ALTER TABLE monimo.metrics_1h        MODIFY SETTING non_replicated_deduplication_window = 100;

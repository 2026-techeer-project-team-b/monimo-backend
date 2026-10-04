# 표에 미친 영향 : `#92`

> 컬럼 정본은 [`db/clickhouse/`](../../../db/clickhouse/) 의 DDL 이다. 여기서는 베끼지 않고 **왜 이 자리에서 건드려야 했나**만 적는다.

## 바꾼 표

**없다.** DDL · 마이그레이션은 한 줄도 안 바꿨다. 그런데도 이 작업이 ERD 와 관련 있는 이유가 이 문서의 전부다.

## 건드린(영향받은) 표

```
                         ┌─ mv_transactions (parent_span_id = '') ─▶ transactions ─ mv_heatmap_1m ─▶ heatmap_1m   (히트맵)
수집기 ─▶ Kafka ─▶ 적재 처리기 ─▶ spans ─┼─ mv_url_stats_1m      (span_kind = SERVER)  ─▶ url_stats_1m          (URL 통계)
   ▲ 여기서 버린다                 ├─ mv_service_health_1m (span_kind = SERVER)  ─▶ service_health_1m     (호출 수 · 에러율 · P95 · 경보 입력)
                                  └─ mv_server_map_1m     (span_kind = CLIENT)  ─▶ server_map_1m         (서버맵, 이번 작업 영향 없음)
```

| 표 | 주인(쓰는 쪽) | 헬스체크가 어떻게 섞였나 | 이번 작업 뒤 |
|---|---|---|---|
| `spans` | 적재 처리기 | SERVER 스팬 한 줄씩 그대로. 실제 파드 SERVER 스팬의 13~15% | 헬스체크 줄이 **안 들어온다** |
| `transactions` ← `mv_transactions` | MV 자동 | 헬스체크는 자식이 없어 `parent_span_id = ''` 이므로 **전부 트랜잭션으로 잡혔다** | 안 들어온다 |
| `heatmap_1m` ← `mv_heatmap_1m` | MV 자동 | 11ms 짜리가 0ms 칸(50ms 버킷의 첫 칸)에 쌓여 히트맵 바닥이 두꺼워졌다 | 안 들어온다 |
| `url_stats_1m` ← `mv_url_stats_1m` | MV 자동 | `GET /actuator/health` 가 URL 순위에 올라왔다 | 안 들어온다 |
| `service_health_1m` ← `mv_service_health_1m` | MV 자동 | 호출 수가 부풀고, 200 응답이라 **에러율 · P95 가 희석**됐다. 로컬 shop-* 253분 | **호출 수가 줄고 에러율 · P95 가 오른다** : 틀린 숫자가 맞는 숫자로 바뀌는 것이지 나빠지는 것이 아니다 |
| `server_map_1m` ← `mv_server_map_1m` | MV 자동 | CLIENT 스팬만 보므로 영향 없음 | 변화 없음. 필터가 CLIENT 를 안 건드리는 이유 |

## 왜 표 주인이 아닌 수집기에서 고쳤나

`spans` 의 주인은 적재 처리기고, 집계 4표의 주인은 MV 다. 그런데 고친 곳은 그 **두 단계 앞**인 수집기다. 이유는 하나다.

**MV 는 INSERT 가 들어오는 순간 계산하고, 그 뒤에는 그 줄을 다시 보지 않는다.** `004_create_materialized_views.sql` 머리에도 적혀 있다 : "MV는 만든 뒤에 들어온 줄만 계산한다. 과거 데이터는 채워 주지 않는다." 그래서

- `spans` 에 **들어가고 나면 늦다.** 조회 쪽에서 `WHERE span_name != 'GET /actuator/health'` 를 붙여도 `service_health_1m` 의 `countState()` 안에 이미 섞여 있다
- MV 자체에 조건을 넣는 것(기각안 ③)은 **세 MV 를 다 고쳐야 하고**, 이미 만들어진 집계는 그대로 틀린 채 남는다. 실제로 히트맵이 `transactions` 를 거친다는 것을 처음엔 못 봐서 셋 중 하나를 빠뜨릴 뻔했다
- 적재 처리기에서 버려도 되지만, 그러면 Kafka 에 넣고 꺼내는 비용이 든 뒤에 버리는 것이다. 수집기에서 버리면 Kafka 도 거치지 않는다

`#83`(`peer_service` 를 적재 **전**에 채운다) 과 같은 이유다. 이 파이프라인에서 집계와 관련된 수정은 **항상 `spans` 에 들어가기 전**이어야 한다.

## 이 표들이 고장 나면

| 가정 | 무슨 일이 생기나 | 막아 둔 것 |
|---|---|---|
| 목록에 `/a` 같은 짧은 값이 들어간다 | 접두 일치였다면 `/api/orders` 가 통째로 `spans` 에서 사라진다. **빠진 줄은 영영 못 되살린다** | 정확 일치만. 틀려도 안 버리는 쪽으로 |
| 필터를 끈다(목록을 비운다) | 다시 섞인다. 끈 구간의 `service_health_1m` 만 틀어진다 | 머지 전후 비교용으로 의도한 동작. `compose.yaml` 이 레포 파일이라 끈 기록이 git 에 남는다 |
| 헬스체크에 자식 CLIENT 스팬이 생긴다 (`validation-query` 지정 · Redis 확인 추가 등) | 부모 SERVER 만 버려져 **자식이 고아**가 된다. 자식은 `parent_span_id != ''` 라 `transactions` 엔 안 가지만 `server_map_1m` 에는 간선으로 남고, 트레이스 상세는 뿌리 없는 트리가 된다 | 지금은 안 생긴다(57 트레이스 = 57 스팬). "부모만 버려지고 자식은 남는다" 를 `HealthCheckFilterTest` 가 고정. 생기면 ADR `#50` 되돌림 조건 |
| 수집기가 여러 대가 된다 | 각 수집기가 같은 env 를 받으면 같이 동작. 한 대만 env 가 다르면 그 대를 거친 헬스체크만 들어온다 | 아직 1대. 여러 대가 되면 env 를 한 곳(deploy 레포 values)에서 뿌린다 |

## 알림 · 화면 파트에 알릴 것

`service_health_1m` 이 **맞아지는 쪽으로** 바뀐다. 머지 뒤 경보(`5XX_RATE` · `4XX_RATE` · `P95_LATENCY`)가 전보다 잘 터지고, 화면의 호출 수는 줄어 보인다. 둘 다 고장이 아니다.

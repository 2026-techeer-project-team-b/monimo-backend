# 승조 : AI 와 일한 기록

> 수집 · 쇼핑몰 · 배포 파트(`@SeungJo-02`)의 기록만 둔다. 다른 파트는 각자 방식으로 일하고, 그 방식을 여기서 대신 말하지 않는다.
> 범위: `collector/` · `ingester/` · `common/` · `db/` · `compose.yaml` · `scripts/` · `.github/` 와 `monimo-shop` · `monimo-deploy` 레포.

이 폴더는 **"무엇을 만들었나" 가 아니라 "어떻게 생각해서 그렇게 만들었나"** 를 남긴다. 코드는 모듈 폴더에, 결정 정본은 [`../design/01-decisions.md`](../design/01-decisions.md) 에 있고, 여기는 그 사이를 잇는다 : 조사 → 프롬프트 → 결정 → 표에 미치는 영향.

## 읽는 순서

처음 보는 사람은 이 순서로 읽으면 20분이면 된다.

1. [`harness.md`](harness.md) : AI 와 일하는 방식. 규칙 파일 · 역할 분리 · 검증 · **AI 가 틀린 것 5건과 어떻게 잡았나**
2. [`92-health-check-filter/`](92-health-check-filter/README.md) : 이 방식으로 처음부터 끝까지 한 첫 이슈. 폴더 안 `README.md` 가 읽는 순서를 안내한다
3. 그 다음 이슈 폴더들 (아래 표)

## 이슈 폴더

이슈 하나 = 폴더 하나. 폴더 이름은 `<이슈번호>-<주제>`. 안에 들어가는 파일은 다섯 가지인데 **그 이슈에서 실제로 한 것만** 둔다. 빈 파일을 만들지 않는다.

| 파일 | 무엇 | 언제 쓰나 |
|---|---|---|
| `README.md` | 한 줄 요약 · 결과물 · 숫자 · 이 폴더 읽는 순서 | 항상 |
| `research.md` | 선택지 비교표 · 출처 · 우리 데이터로 확인한 것 · AI 가 틀린 것 · 면접 질문 | 선택지가 2개 이상 있었을 때 |
| `prompts.md` | 조사 · 구현 프롬프트 **원문 그대로** · 한 번에 됐나 · 고쳐 물은 말 | 항상 |
| `decision.md` | ADR 번호 · 4요소(채택 · 기각 · 사유 · 되돌림) 요약 · 결정을 만든 프롬프트가 어디 있나 | 설계 결정이 걸렸을 때 |
| `erd.md` | 건드린 표 · 표 주인 · 왜 이 자리에서 바꿔야 했나 · 이 표가 고장 나면 | 표나 집계(MV)에 영향이 있을 때 |

| 폴더 | 이슈 · PR | 있는 것 | 비고 |
|---|---|---|---|
| [`92-health-check-filter/`](92-health-check-filter/README.md) | `#92` · PR `#93` | README · research · prompts · decision · erd | **이 방식의 첫 이슈.** 결정 프롬프트 원문이 ADR `#50` 안에 있다 |
| [`83-peer-service/`](83-peer-service/README.md) | `#83` · PR `#84` | README · prompts | 소급. 이 방식 전이라 프롬프트 로그만 있다 |

## 규칙

- **코드와 같은 PR 에 넣는다.** 나중에 몰아 쓰면 안 쓴다
- 프롬프트는 **원문 그대로.** 고쳐 쓰지 않는다. 짧았으면 짧은 채로
- 조사 답은 **출처로 확인**한다. 순서: 공식 문서 → 오픈소스 코드 → 기술 블로그. 링크를 못 찾은 주장은 **미확인**이라고 적는다
- **AI 가 틀렸던 것을 지우지 않는다.** 그게 "어떻게 검증했나" 의 답이다
- 결정은 여기 쓰지 않는다. [`../design/01-decisions.md`](../design/01-decisions.md) 에 4요소로 쓰고 `decision.md` 는 번호와 요약만 둔다. 결정을 프롬프트로 내렸으면 그 원문도 ADR 안에 둔다(`#50` 이 첫 사례)
- `erd.md` 는 컬럼 표를 베끼지 않는다. 컬럼 정본은 `db/*.sql` 이고, 여기는 **왜 이 표를 이 자리에서 건드렸나**만 적는다
- 면접 질문은 코드를 안 봐도 답할 수 있는 것으로. "왜 X 를 골랐나 / Y 가 죽으면 어떻게 되나" 꼴
- 글은 평서 단정형(~한다, ~다). 이모지 금지. em dash 대신 `:`

## 새 이슈 폴더 만드는 법

```bash
cp -r docs/seungjo/TEMPLATE docs/seungjo/<이슈번호>-<주제>
# 안 쓰는 파일은 지운다. 빈 파일을 남기지 않는다
```

[`TEMPLATE/`](TEMPLATE/) 안의 다섯 파일이 각각 틀이다.

## 다른 레포

`monimo-shop` · `monimo-deploy` · `monimo-web` · `monimo-watchdog` 의 `docs/prompts/` 는 **그 레포에서 한 작업**의 프롬프트 로그다. 규칙과 틀은 이 폴더가 정본이다.

## 같이 보는 것

- 결정 기록 : [`../design/01-decisions.md`](../design/01-decisions.md)
- 고장 나면 어떻게 되나 : [`../design/30-failure-modes.md`](../design/30-failure-modes.md)
- 규칙 · 한 일 · 막힌 것 : [`AGENTS.md`](../../AGENTS.md)

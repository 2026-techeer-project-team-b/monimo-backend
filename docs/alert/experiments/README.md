# 알림 실험 도구

| 파일 | 하는 일 |
|---|---|
| `fake_slack_hold.py <port> <log> <hold>` | 가짜 Slack. 받는 즉시 수신 시각 · 본문을 `<log>` 에 한 줄(fsync). `<hold>` 파일이 있으면 그 안의 초만큼 응답을 늦춘다 → "외부는 받았는데 발신자는 결과를 모르는" 구간을 만든다 |
| `enqueue.sh <tag> <n>` | 탐지가 넣었을 사건 + FIRING 발송 작업을 n 개 넣는다 (채널 2 · shop-order, 한 그룹) |
| `notifier.sh <name>` | notifier jar 를 JVM 으로 직접 띄운다. `kill -9 $(cat $OUT/notifier.pid)` 로 실제 프로세스를 죽인다 |

전제: 로컬 compose(PostgreSQL), 채널 2 의 webhook_url = `http://127.0.0.1:18999/hook`, `./gradlew :notifier:bootJar`.
실패 지점은 코드 훅 없이 가짜 Slack 의 응답 지연으로 통제한다 (운영 코드에 실험용 분기를 넣지 않는다).

# 알림 스키마 제안 SQL (팀 미확정)

- `alert/` : 알림 표 제안. 합의되면 `db/postgres/alert/` 로 옮긴다 (파일 이름 시각은 그때 다시 매긴다).
- `stub/` : **테스트 전용 대역**. `applications` · `agents` 는 다른 파트 표라서 여기서 만들지 않는다.
  주인 파트가 `db/postgres/` 에 실제 표를 넣으면 이 폴더를 지운다.
- 적용: detector · notifier 의 통합 테스트만 `spring.flyway.locations` 에 이 폴더를 더한다. 다른 모듈 · CI 에는 영향이 없다.

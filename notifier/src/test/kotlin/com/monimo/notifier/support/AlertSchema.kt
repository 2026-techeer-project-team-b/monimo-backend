package com.monimo.notifier.support

// 알림 제안 스키마(docs/alert/sql)를 db/postgres 에 더해 적용한다. 합의 뒤 db/postgres/alert 로 옮기면 이 설정은 지운다
const val ALERT_SCHEMA_FLYWAY = "spring.flyway.locations=filesystem:../db/postgres,filesystem:../docs/alert/sql"

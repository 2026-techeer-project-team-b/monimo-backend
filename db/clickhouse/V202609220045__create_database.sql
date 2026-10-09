-- ClickHouse 초기 DDL. Flyway 가 버전 순서(V202609220045, V202609221904, ...)대로 안 돌린 것만 돌린다.
-- 원본 4표(1904) → 집계 7표(1905) → MV 7개(1907) 순서다 (MV는 원본·집계 표가 먼저 있어야 만들 수 있다).
-- 정본은 노션 ERD「CH 영역」. 표를 바꾸면 ERD도 같이 고친다.
-- 이미 돌린 파일은 다시 돌지 않는다. 고치면 체크섬이 어긋나 Flyway 가 멈추고 알려 준다.
-- 스키마를 바꿀 때는 이 파일을 고치지 않고 새 V 파일을 더한다 (db/clickhouse/README.md).
-- 1b에서 monimo-deploy/schema/clickhouse 로 옮긴다 (ADR #46 · 개발환경 계획 5단계).
CREATE DATABASE IF NOT EXISTS monimo;

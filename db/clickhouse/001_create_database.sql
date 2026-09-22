-- ClickHouse 초기 DDL. 파일 이름 순서(001, 002, ...)대로 처음 켤 때 한 번 실행된다.
-- 002 원본 4표 → 003 집계 7표 → 004 MV 7개 순서다 (MV는 원본·집계 표가 먼저 있어야 만들 수 있다).
-- 정본은 노션 ERD「CH 영역」. 표를 바꾸면 ERD도 같이 고친다.
-- 이미 켜 둔 데이터에는 다시 실행되지 않는다. 바꾼 뒤에는 `docker compose down -v` 후 다시 켠다.
-- 1b에서 monimo-deploy/schema/clickhouse 로 옮긴다 (ADR #46 · 개발환경 계획 5단계).
CREATE DATABASE IF NOT EXISTS monimo;

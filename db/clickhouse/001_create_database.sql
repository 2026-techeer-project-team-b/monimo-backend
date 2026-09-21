-- ClickHouse 초기 DDL. 파일 이름 순서(001, 002, ...)대로 처음 켤 때 한 번 실행된다.
-- 표 정의(spans · logs · metrics_raw ...)는 적재 처리기 작업(개발환경 8단계)에서 002부터 추가한다.
-- 1b에서 monimo-deploy/schema/clickhouse 로 옮긴다 (ADR #46 · 개발환경 계획 5단계).
CREATE DATABASE IF NOT EXISTS monimo;

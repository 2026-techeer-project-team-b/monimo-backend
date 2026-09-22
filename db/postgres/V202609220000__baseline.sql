-- 마이그레이션 시작점. 표는 파트별 폴더(config/ · alert/ · ingest/)에 새 파일로 추가한다. 규칙은 db/postgres/README.md
COMMENT ON SCHEMA public IS '모니모니터링 상태 저장소. 표마다 주인은 하나 (ADR #20 #36 #39), 마이그레이션은 db/postgres 한 곳 (ADR #49)';

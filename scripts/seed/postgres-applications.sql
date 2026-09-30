-- 가짜 감시 대상 서비스. ClickHouse 가짜 신호(db/clickhouse/seed/001_fake_signals.sql)와 같은 이름이어야
-- 화면의 서비스 목록과 CH 조회가 이어진다. 두 저장소를 잇는 끈은 이 이름 하나뿐이다.
--
-- 넣는 법: ./scripts/seed-clickhouse.sh (CH 와 함께 들어간다)
--
-- db/postgres/ 아래에 두지 않는 이유: Flyway 가 그 폴더를 하위까지 훑어 마이그레이션으로 읽는다.
-- 이름 규칙(V{년월일시분}__)에 안 맞으면 조용히 무시하지 않고 실행 전체가 실패하도록 해 두었다 (ADR #49).
INSERT INTO applications (name, display_name, description)
VALUES ('shop-gateway',   '쇼핑몰 게이트웨이', '감시 대상 쇼핑몰의 입구. 가짜 데이터용'),
       ('shop-order',     '쇼핑몰 주문',       '주문 생성 · 조회. 가짜 데이터용'),
       ('shop-payment',   '쇼핑몰 결제',       '결제 승인. 가짜 데이터용'),
       ('shop-inventory', '쇼핑몰 재고',       '재고 예약. 가짜 데이터용')
ON CONFLICT (name) DO NOTHING;

-- 설정 줄도 같이 만든다. 평소에는 API 서버가 서비스 등록 때 만들지만, 가짜 데이터는 그 문을 거치지 않는다
INSERT INTO application_configs (application_id, sampling_rate)
SELECT id, 0.0100 FROM applications WHERE name LIKE 'shop-%'
ON CONFLICT (application_id) DO NOTHING;

-- 가짜 파드 8개. ClickHouse 가짜 신호(clickhouse-fake-signals.sql, 같은 폴더)의 agent_id 와 같은 글자여야
-- 인스펙터의 파드 목록에서 지표 · 스레드 덤프 조회로 이어진다. 평소에는 적재 처리기가 처음 본 파드를 넣는다.
--
-- 넣는 법: ./scripts/seed-clickhouse.sh (CH 와 함께 들어간다)
--
-- 다시 돌리면 지우지 않고 status 만 UP 으로 되돌린다: alert_events · alert_evaluation_states 가 agents 를 FK 로 가리킨다.
-- UP 으로 넣는 이유: 화면의 「스레드 덤프 요청」 버튼이 UP 일 때만 켜진다. 신호가 끊기면 탐지가 90초 뒤 DOWN 으로 바꾼다 (ADR #39)
INSERT INTO agents (application_id, agent_key, hostname, ip, jvm_version, agent_version, status, first_seen_at)
SELECT a.id, p.agent_key, p.agent_key, p.ip::inet, '17.0.12', '2.31.1', 'UP', now() - interval '1 hour'
FROM (VALUES
    ('shop-gateway',   'shop-gateway-6d8f7b-k2x9p',   '10.0.1.11'),
    ('shop-gateway',   'shop-gateway-6d8f7b-r7mq4',   '10.0.1.12'),
    ('shop-order',     'shop-order-7c9d5f-2xk8p',     '10.0.2.11'),
    ('shop-order',     'shop-order-7c9d5f-b4n7t',     '10.0.2.12'),
    ('shop-inventory', 'shop-inventory-5b6c8d-h3v2w', '10.0.3.11'),
    ('shop-inventory', 'shop-inventory-5b6c8d-q9z5c', '10.0.3.12'),
    ('shop-payment',   'shop-payment-84f6c9-m5t1s',   '10.0.4.11'),
    ('shop-payment',   'shop-payment-84f6c9-x8d3f',   '10.0.4.12')
) AS p(service_name, agent_key, ip)
JOIN applications a ON a.name = p.service_name
ON CONFLICT (agent_key) DO UPDATE SET status = 'UP', updated_at = now();

-- agents(수집 파트) 표가 생겼으므로 보류해 둔 FK 를 붙인다.
-- 원래 주석: V202609281821__create_alert_events.sql · V202609281822__create_alert_evaluation_states.sql 의
--           "agents 표가 아직 없어 FK 는 뒤로 미룬다. 생기면 alter 파일로 REFERENCES agents (id) 를 붙인다"
--
-- agent_id 는 파드 단위 규칙(CPU · HEAP · GC_TIME · AGENT_DOWN)일 때만 채워진다. 서비스 단위 규칙이면 NULL 이라
-- 컬럼은 NULL 을 허용한 채로 두고 FK 만 건다. NULL 은 FK 검사를 거치지 않는다.
ALTER TABLE alert_events
    ADD CONSTRAINT fk_alert_events_agent FOREIGN KEY (agent_id) REFERENCES agents (id);

ALTER TABLE alert_evaluation_states
    ADD CONSTRAINT fk_alert_evaluation_states_agent FOREIGN KEY (agent_id) REFERENCES agents (id);

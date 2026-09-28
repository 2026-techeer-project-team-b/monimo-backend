-- [제안] 경보 평가 상태 (FN-28 N · M 카운터). 주인: 탐지. fingerprint 하나당 한 줄, 평가 트랜잭션이 FOR UPDATE 로 잡는다
CREATE TABLE alert_evaluation_states (
    id                    BIGSERIAL   PRIMARY KEY,
    fingerprint           VARCHAR(64) NOT NULL UNIQUE,
    alert_rule_id         BIGINT      NOT NULL REFERENCES alert_rules (id),
    -- agents(수집 파트) 표가 아직 없어 FK 는 뒤로 미룬다. 생기면 alter 파일로 REFERENCES agents (id) 를 붙인다
    agent_id              BIGINT,
    phase                 VARCHAR(10) NOT NULL CHECK (phase IN ('NORMAL', 'PENDING', 'FIRING')),
    consecutive_bad       INT         NOT NULL DEFAULT 0,
    consecutive_good      INT         NOT NULL DEFAULT 0,
    consecutive_unknown   INT         NOT NULL DEFAULT 0,
    active_alert_event_id BIGINT      REFERENCES alert_events (id),
    last_bucket_end       TIMESTAMPTZ,
    rule_version          INT         NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CHECK ((phase = 'FIRING') = (active_alert_event_id IS NOT NULL))
);

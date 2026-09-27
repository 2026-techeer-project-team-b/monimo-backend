-- 경보 사건. 주인: 탐지. 노션 ERD + 발화 당시 규칙 스냅샷(제안) + 진행 중 사건 부분 UNIQUE(제안)
CREATE TABLE alert_events (
    id               BIGSERIAL     PRIMARY KEY,
    alert_event_uuid UUID          NOT NULL UNIQUE,
    alert_rule_id    BIGINT        NOT NULL REFERENCES alert_rules (id),
    agent_id         BIGINT        REFERENCES agents (id),
    fingerprint      VARCHAR(64)   NOT NULL,
    state            VARCHAR(20)   NOT NULL CHECK (state IN ('FIRING', 'RESOLVED')),
    observed_value   NUMERIC(12,4),
    fired_at         TIMESTAMPTZ   NOT NULL,
    resolved_at      TIMESTAMPTZ,
    -- [제안] 발화 당시 규칙. 규칙을 고쳐도 과거 사건 상세(API #15)가 바뀌지 않게
    rule_version     INT           NOT NULL,
    metric_kind      VARCHAR(30)   NOT NULL,
    operator         VARCHAR(5)    NOT NULL,
    threshold        NUMERIC(12,4) NOT NULL,
    window_sec       INT           NOT NULL,
    severity         VARCHAR(20)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- [제안] 같은 규칙 + 대상에 진행 중 사건은 하나뿐. RESOLVED 는 빠지므로 복구 뒤 재발은 막지 않는다 (탐지 경쟁의 최종 방어선)
CREATE UNIQUE INDEX alert_events_one_firing_per_fingerprint ON alert_events (fingerprint) WHERE state = 'FIRING';
CREATE INDEX alert_events_fired_at ON alert_events (fired_at DESC, id DESC);

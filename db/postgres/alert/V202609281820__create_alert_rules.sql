-- 경보 규칙 · 채널 · 연결. 주인: API 서버. 노션 ERD + version(규칙 판 번호, 제안)
CREATE TABLE alert_rules (
    id              BIGSERIAL     PRIMARY KEY,
    alert_rule_uuid UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    application_id  BIGINT        NOT NULL REFERENCES applications (id),
    name            VARCHAR(200)  NOT NULL,
    metric_kind     VARCHAR(30)   NOT NULL CHECK (metric_kind IN ('5XX_RATE', '4XX_RATE', 'P95_LATENCY', 'CPU', 'HEAP', 'GC_TIME', 'AGENT_DOWN')),
    operator        VARCHAR(5)    NOT NULL CHECK (operator IN ('GT', 'GTE', 'LT', 'LTE')),
    threshold       NUMERIC(12,4) NOT NULL,
    window_sec      INT           NOT NULL CHECK (window_sec > 0),
    severity        VARCHAR(20)   NOT NULL CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    enabled         BOOLEAN       NOT NULL DEFAULT true,
    -- [제안] 조건을 고칠 때마다 +1. 탐지가 수정 전 규칙으로 계산한 평가를 버리는 기준
    version         INT           NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE alert_channels (
    id                 BIGSERIAL    PRIMARY KEY,
    alert_channel_uuid UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name               VARCHAR(100) NOT NULL,
    type               VARCHAR(20)  NOT NULL CHECK (type IN ('SLACK', 'EMAIL', 'WEBHOOK', 'PAGERDUTY')),
    config             JSONB        NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE alert_rule_channels (
    id               BIGSERIAL   PRIMARY KEY,
    alert_rule_id    BIGINT      NOT NULL REFERENCES alert_rules (id),
    alert_channel_id BIGINT      NOT NULL REFERENCES alert_channels (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (alert_rule_id, alert_channel_id)
);

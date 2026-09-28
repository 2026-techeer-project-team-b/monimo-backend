-- [제안 A안] 발송 아웃박스 = 채널별 발송 큐. 탐지가 사건 전이와 같은 트랜잭션에서 INSERT, 알림이 상태 컬럼을 UPDATE
CREATE TABLE notification_outbox (
    id               BIGSERIAL   PRIMARY KEY,
    alert_event_id   BIGINT      NOT NULL REFERENCES alert_events (id),
    transition       VARCHAR(10) NOT NULL CHECK (transition IN ('FIRING', 'RESOLVED')),
    alert_channel_id BIGINT      NOT NULL REFERENCES alert_channels (id),
    payload          JSONB       NOT NULL,
    status           VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED', 'CANCELLED')),
    attempt_count    INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    lease_until      TIMESTAMPTZ,
    claim_token      UUID,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    -- 논리 발송 1건 = 사건 × 전이 × 채널. 같은 전이를 두 번 넣으면 실패한다 (전달 의도의 멱등성)
    UNIQUE (alert_event_id, transition, alert_channel_id),
    CHECK ((status = 'IN_FLIGHT') = (claim_token IS NOT NULL AND lease_until IS NOT NULL))
);

-- 워커가 매 폴링마다 읽는 "지금 보낼 것" 범위. 끝난 줄(SENT · FAILED · CANCELLED)은 인덱스에 들어가지 않는다
CREATE INDEX notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');

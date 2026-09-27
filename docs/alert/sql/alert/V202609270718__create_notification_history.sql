-- 알림 전송 이력. 주인: 알림. 노션 ERD 그대로. [제안] 한 줄 = 논리 발송 1건의 최종 결과, retry_count = 시도 수 - 1
CREATE TABLE notification_history (
    id                BIGSERIAL   PRIMARY KEY,
    notification_uuid UUID        NOT NULL UNIQUE,
    alert_event_id    BIGINT      NOT NULL REFERENCES alert_events (id),
    alert_channel_id  BIGINT      NOT NULL REFERENCES alert_channels (id),
    result            VARCHAR(20) NOT NULL CHECK (result IN ('SUCCESS', 'FAIL')),
    retry_count       INT         NOT NULL DEFAULT 0,
    response          TEXT,
    sent_at           TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_history_event ON notification_history (alert_event_id, sent_at DESC, id DESC);

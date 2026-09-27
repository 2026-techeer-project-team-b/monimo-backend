-- 앱 설정 (재배포 없이 바꾸는 값). 서비스당 한 줄. 소유: API 서버 (ADR #36), 수집기가 30초 캐시로 읽는다 (ADR #37)
CREATE TABLE application_configs (
    id                      BIGSERIAL    PRIMARY KEY,
    application_config_uuid UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    application_id          BIGINT       NOT NULL UNIQUE REFERENCES applications (id),
    sampling_rate           NUMERIC(5,4) NOT NULL CHECK (sampling_rate >= 0 AND sampling_rate <= 1),
    version                 INT          NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by              BIGINT       REFERENCES users (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN application_configs.sampling_rate IS '0.0100 = 100건 중 1건. 수집기가 trace_id 해시로 이 비율만 통과시킨다';
COMMENT ON COLUMN application_configs.version IS '고칠 때마다 +1. PUT 의 expected_version 과 다르면 409 CONFIG_VERSION_CONFLICT';
COMMENT ON COLUMN application_configs.updated_by IS '마지막으로 고친 사용자. 처음 만든 뒤 아무도 안 고쳤으면 NULL';

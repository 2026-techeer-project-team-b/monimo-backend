-- 감시 대상 서비스. 소유: API 서버 (ADR #36)
CREATE TABLE applications (
    id               BIGSERIAL    PRIMARY KEY,
    application_uuid UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL UNIQUE,
    display_name     VARCHAR(200),
    description      TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 두 저장소를 잇는 유일한 이름표라 등록 뒤에는 바꾸지 않는다 (API 서버 PATCH 도 name 은 받지 않는다)
COMMENT ON COLUMN applications.name IS 'CH 모든 표의 service_name 과 같은 글자 (otel.service.name)';

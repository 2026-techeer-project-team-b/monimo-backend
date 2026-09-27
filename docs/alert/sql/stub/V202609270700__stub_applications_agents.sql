-- [테스트 전용 대역] 노션 ERD 의 applications · agents 중 알림 FK 에 필요한 컬럼만. 실제 표는 주인 파트가 db/postgres 에 만든다
CREATE TABLE applications (
    id               BIGSERIAL    PRIMARY KEY,
    application_uuid UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE agents (
    id             BIGSERIAL    PRIMARY KEY,
    agent_uuid     UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    agent_key      VARCHAR(100) NOT NULL UNIQUE,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

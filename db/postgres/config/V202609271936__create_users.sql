-- 사용자 (로그인 계정). 소유: API 서버 (ADR #36)
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    user_uuid     UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100),
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN users.email IS '로그인 아이디. CH thread_dumps.requested_by 에 이 글자가 그대로 남는다';
COMMENT ON COLUMN users.role IS 'ADMIN = 설정·규칙 편집 가능, VIEWER = 보기만';

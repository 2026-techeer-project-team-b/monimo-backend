-- 발급한 refresh 토큰. 재발급마다 옛 줄을 지우고 새 줄을 넣고(회전), 로그아웃은 줄을 지운다. 소유: API 서버
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 원문은 저장하지 않는다. DB 가 새어도 토큰을 쓸 수 없게
COMMENT ON COLUMN refresh_tokens.token_hash IS 'refresh 토큰 원문의 SHA-256 (hex 64자)';

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);

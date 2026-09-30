-- 에이전트 인스턴스(파드). 소유: 적재 처리기(등록) · 탐지(생존 상태 갱신) — ADR #39
-- 줄을 넣는 것은 적재 처리기다. CH 에 넣다가 처음 보는 agent_key 가 나오면 INSERT ... ON CONFLICT DO NOTHING 한다.
CREATE TABLE agents (
    id             BIGSERIAL    PRIMARY KEY,
    agent_uuid     UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    application_id BIGINT       NOT NULL REFERENCES applications (id),
    agent_key      VARCHAR(100) NOT NULL UNIQUE,
    hostname       VARCHAR(255),
    ip             INET,
    jvm_version    VARCHAR(50),
    agent_version  VARCHAR(50),
    status         VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UP', 'DOWN', 'UNKNOWN')),
    first_seen_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 서비스별 파드 목록 화면이 이 순서로 읽는다
CREATE INDEX idx_agents_application ON agents (application_id, status);

COMMENT ON TABLE agents IS '에이전트 인스턴스(파드). 적재 처리기가 등록하고 탐지가 status 를 갱신한다';
COMMENT ON COLUMN agents.agent_key IS 'CH 모든 표의 agent_id 와 같은 글자. 에이전트가 스스로 붙여 보낸다 (PG 의 숫자 FK 와 헷갈리지 않게 key 로 부른다)';
COMMENT ON COLUMN agents.status IS 'UP · DOWN · UNKNOWN. 탐지가 "CH 에 90초 동안 이 파드 데이터 없음" 규칙으로 DOWN 처리한다 (ADR #39)';
COMMENT ON COLUMN agents.ip IS '수집기가 연결 통로에서 알아내 메시지에 붙이고 적재 처리기가 채운다. 스레드 덤프는 이 주소로 가지 않는다';
COMMENT ON COLUMN agents.first_seen_at IS '이 파드를 처음 본 시각. 배포 시점을 가늠하는 데 쓴다';

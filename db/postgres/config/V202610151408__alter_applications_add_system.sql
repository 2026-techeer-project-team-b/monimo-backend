-- #205 : 서비스가 속한 시스템. 수집기가 샘플링 비율을 시스템 안 최댓값으로 묶는 축 (ADR #53 보완, #206).
-- 같은 트레이스를 지나는 서비스끼리는 같은 비율이어야 하지만 시스템이 다르면 서로 호출하지 않아 묶을 이유가 없다
ALTER TABLE applications ADD COLUMN system VARCHAR(50) NOT NULL DEFAULT 'shop';

COMMENT ON COLUMN applications.system IS '서비스가 속한 시스템. 같은 값끼리 샘플링 비율의 최댓값을 묶는다. 기본 shop';

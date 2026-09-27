-- 감시 대상에서 제외한 시각. alert_rules · agents 가 NOT NULL FK 로 참조해 줄을 지울 수 없어 표시만 한다 (API DELETE /applications)
ALTER TABLE applications ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN applications.deleted_at IS '제외한 시각. 감시 중이면 NULL. 목록 · 상세 · 설정 조회에서 빠진다';

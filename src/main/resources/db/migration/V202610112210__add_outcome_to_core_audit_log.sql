-- 그 일이 됐는지(DONE) 거부됐는지(DENIED)를 기록에 남긴다. 거부된 시도만 골라 볼 수 있게 한다.
-- 이 컬럼이 없을 때 적힌 줄은 DONE으로 채운다. 그 뒤에는 기본값 없이 적는 쪽이 항상 값을 넣는다.
ALTER TABLE core_audit_log
    ADD COLUMN outcome VARCHAR(10) NOT NULL DEFAULT 'DONE' AFTER action;

ALTER TABLE core_audit_log
    ALTER COLUMN outcome DROP DEFAULT;

ALTER TABLE core_audit_log
    ADD KEY idx_core_audit_log_outcome (outcome, occurred_at);

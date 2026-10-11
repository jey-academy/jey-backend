-- 감사 로그. 누가 언제 무엇을 했는지 남긴다. 추가만 하고 고치거나 지우지 않는다(ADR-0024).
-- 전화번호나 이름 같은 개인정보는 싣지 않는다. ID만 싣는다.
CREATE TABLE core_audit_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    -- 그 일이 일어난 시각. created_at은 이 줄이 적힌 시각이다. 이벤트로 들어온 기록은 둘이 다르다.
    occurred_at DATETIME(6)   NOT NULL,
    -- 로그인 없이 일어난 일(배치, 웹훅)은 NULL이다.
    -- 외래 키를 걸지 않는다. 계정이 지워져도 기록은 남아야 한다.
    actor_id    BIGINT,
    -- {모듈}.{사건}. 이벤트로 들어온 기록은 이벤트 이름이 그대로 들어간다.
    action      VARCHAR(80)   NOT NULL,
    target_type VARCHAR(40),
    target_id   BIGINT,
    campus_id   BIGINT,
    detail_json VARCHAR(2000),
    -- 이벤트로 들어온 기록만 갖는다. 같은 이벤트가 다시 와도 한 줄만 남게 한다.
    event_id    CHAR(36),
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    -- 이 줄을 저장한 요청의 계정. 이벤트로 들어온 기록은 요청 밖에서 저장되므로 NULL이다. 누가 했는지는 actor_id를 본다.
    created_by  BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_audit_log_event (event_id),
    KEY idx_core_audit_log_occurred (occurred_at),
    KEY idx_core_audit_log_actor (actor_id, occurred_at),
    KEY idx_core_audit_log_action (action, occurred_at),
    KEY idx_core_audit_log_target (target_type, target_id, occurred_at)
) ENGINE = InnoDB;

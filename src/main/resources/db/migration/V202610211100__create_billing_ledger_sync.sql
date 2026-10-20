-- 결제 하나를 지점의 장부(구글 시트)에 쓰는 일의 상태. 결제마다 한 줄이다.
-- 시트에 쓰는 일은 결제 트랜잭션 밖에서 하므로, 쓰지 못한 결제를 이 표로 찾아 다시 시도하거나 관리자에게 보인다.
-- 한 번에 한 쪽만 쓴다: 행을 잠가 읽고 PENDING(다음 시도 시각이 됨) 또는 오래된 IN_PROGRESS일 때만 맡는다.
-- reason은 UNSYNCED일 때만 있다. canceled_on은 결제를 취소했다는 요청이고, 취소 처리를 마치면 CANCELED가 된다.
-- 학생·지점·대상 월은 결제·청구에서 복사해 둔다(관리자 목록과 탭 이름을 조인 없이 읽는다).
CREATE TABLE billing_ledger_sync (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id      BIGINT      NOT NULL,
    campus_id       BIGINT      NOT NULL,
    student_id      BIGINT      NOT NULL,
    target_month    CHAR(7)     NOT NULL,
    status          VARCHAR(20) NOT NULL,
    reason          VARCHAR(40),
    attempts        INT         NOT NULL,
    next_attempt_at DATETIME(6),
    last_attempt_at DATETIME(6),
    sheet_tab       VARCHAR(20),
    sheet_row       INT,
    synced_at       DATETIME(6),
    canceled_on     DATE,
    cancel_marked   BOOLEAN     NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    created_by      BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_ledger_sync_payment (payment_id),
    KEY ix_billing_ledger_sync_due (status, next_attempt_at),
    KEY ix_billing_ledger_sync_campus (campus_id, status),
    CONSTRAINT fk_billing_ledger_sync_payment FOREIGN KEY (payment_id) REFERENCES billing_payment (id),
    CONSTRAINT ck_billing_ledger_sync_reason CHECK ((status = 'UNSYNCED') = (reason IS NOT NULL)),
    CONSTRAINT ck_billing_ledger_sync_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB;

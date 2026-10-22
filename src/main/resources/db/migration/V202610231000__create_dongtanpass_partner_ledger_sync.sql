-- 패스 사용 하나를 연계 학원의 장부(구글 시트)에 쓰는 일의 상태. 패스는 한 번만 쓰이므로 패스마다 한 줄이다.
-- 시트에 쓰는 일은 사용 처리 트랜잭션 밖에서 하므로, 쓰지 못한 기록을 이 표로 찾아 다시 시도하거나 관리자에게 보인다.
-- 한 번에 한 쪽만 쓴다: 행을 잠가 읽고 PENDING(다음 시도 시각이 됨) 또는 오래된 IN_PROGRESS일 때만 맡는다.
-- partner_id는 장부의 주인(할인해 준 학원, 또는 패스를 발급한 학원)이다. reason은 UNSYNCED일 때만 있다.
CREATE TABLE dongtanpass_partner_ledger_sync (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    pass_id         BIGINT      NOT NULL,
    partner_id      BIGINT      NOT NULL,
    kind            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    reason          VARCHAR(40),
    attempts        INT         NOT NULL,
    next_attempt_at DATETIME(6),
    last_attempt_at DATETIME(6),
    sheet_row       INT,
    synced_at       DATETIME(6),
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    created_by      BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_partner_ledger_sync_pass (pass_id),
    KEY ix_dongtanpass_partner_ledger_sync_due (status, next_attempt_at),
    KEY ix_dongtanpass_partner_ledger_sync_partner (partner_id, status),
    CONSTRAINT fk_dongtanpass_partner_ledger_sync_pass FOREIGN KEY (pass_id) REFERENCES dongtanpass_pass (id),
    CONSTRAINT fk_dongtanpass_partner_ledger_sync_partner FOREIGN KEY (partner_id) REFERENCES dongtanpass_partner (id),
    CONSTRAINT ck_dongtanpass_partner_ledger_sync_kind CHECK (kind IN ('PARTNER_DISCOUNT', 'USED_AT_JEY')),
    CONSTRAINT ck_dongtanpass_partner_ledger_sync_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SYNCED', 'UNSYNCED')),
    CONSTRAINT ck_dongtanpass_partner_ledger_sync_reason_value CHECK (reason IS NULL OR reason IN ('GOOGLE_UNAVAILABLE',
        'NOT_CONFIGURED', 'TAB_NOT_FOUND', 'HEADER_MISMATCH', 'ACCESS_DENIED', 'GOOGLE_REJECTED', 'APP_ERROR')),
    CONSTRAINT ck_dongtanpass_partner_ledger_sync_reason CHECK ((status = 'UNSYNCED') = (reason IS NOT NULL)),
    CONSTRAINT ck_dongtanpass_partner_ledger_sync_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB;

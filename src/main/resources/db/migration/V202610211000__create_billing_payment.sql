-- 결제한 청구(PAID)도 열린 청구다. 결제한 달에 청구를 하나 더 만들 수 없어야 한다. 닫힌 청구는 취소한 것(CANCELED)뿐이다.
-- 이전 CHECK는 ISSUED만 열린 청구로 보았다(그때는 상태가 ISSUED 하나였다).
ALTER TABLE billing_invoice DROP CHECK ck_billing_invoice_open_key;
ALTER TABLE billing_invoice
    ADD CONSTRAINT ck_billing_invoice_open_key CHECK ((status IN ('ISSUED', 'PAID') AND open_key <=> 1)
        OR (status = 'CANCELED' AND open_key IS NULL));

-- 데스크가 받은 결제의 기록(포스기 카드, 현금). 앱은 결제를 하지 않고 받은 결과를 적는다.
-- 수강료(amount)는 청구 합계와 같다(나눠 내기 없음). 교재비(book_fee)는 청구 계산과 무관하게 따로 적는다.
-- 청구 하나에 유효한 결제(COMPLETED)는 하나다. 취소한 결제는 남기고 open_key를 NULL로 비운다(청구와 같은 방식).
-- 학생·지점은 청구에서 복사해 둔다(지점별 결제 목록을 청구와 조인하지 않고 읽는다).
CREATE TABLE billing_payment (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    invoice_id  BIGINT         NOT NULL,
    campus_id   BIGINT         NOT NULL,
    student_id  BIGINT         NOT NULL,
    method      VARCHAR(20)    NOT NULL,
    amount      DECIMAL(15, 0) NOT NULL,
    book_fee    DECIMAL(15, 0) NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    open_key    TINYINT,
    paid_at     DATETIME(6)    NOT NULL,
    canceled_at DATETIME(6),
    created_at  DATETIME(6)    NOT NULL,
    updated_at  DATETIME(6)    NOT NULL,
    created_by  BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_payment_open (invoice_id, open_key),
    KEY ix_billing_payment_campus_paid (campus_id, paid_at),
    KEY ix_billing_payment_paid (paid_at),
    CONSTRAINT fk_billing_payment_invoice FOREIGN KEY (invoice_id) REFERENCES billing_invoice (id),
    -- open_key가 NULL이면 = 비교가 "알 수 없음"이 되어 CHECK를 지나간다. <=>로 비교한다.
    CONSTRAINT ck_billing_payment_open_key CHECK ((status = 'COMPLETED' AND open_key <=> 1)
        OR (status = 'CANCELED' AND open_key IS NULL)),
    CONSTRAINT ck_billing_payment_canceled_at CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL)),
    CONSTRAINT ck_billing_payment_amounts CHECK (amount >= 0 AND book_fee >= 0)
) ENGINE = InnoDB;

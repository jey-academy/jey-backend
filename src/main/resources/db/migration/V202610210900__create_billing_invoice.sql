-- 수강료 청구. 학생 한 명의 한 달(대상 월) 수강료. 반 나눔이면 라인이 둘 이상이고, 라인마다 할인이 여러 개다.
-- 금액은 만든 순간의 계산을 그대로 남긴다(수강료표나 할인율이 바뀌어도 따라 바뀌지 않는다).
-- 학생·반·지점은 다른 모듈(core)의 자료라 ID만 갖는다(외래 키를 걸지 않는다).

-- 한 학생 한 달에 열린 청구는 하나다. open_key는 열린 청구만 1이고 닫힌 청구(취소 등)는 NULL이라,
-- (student_id, target_month, open_key) 유니크는 열린 청구끼리만 겹치지 않게 막는다(NULL은 서로 겹치지 않는다).
CREATE TABLE billing_invoice (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    campus_id       BIGINT         NOT NULL,
    student_id      BIGINT         NOT NULL,
    -- 'YYYY-MM'
    target_month    CHAR(7)        NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    open_key        TINYINT,
    -- 계산에 쓴 할인 방식(ADDITIVE, SEQUENTIAL, SINGLE). 설정이 바뀌어도 이 청구는 그대로다.
    discount_policy VARCHAR(20)    NOT NULL,
    -- 라인 청구액의 합(합계에서 다시 끊지 않는다)
    total_amount    DECIMAL(15, 0) NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    created_by      BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_invoice_open (student_id, target_month, open_key),
    KEY ix_billing_invoice_campus_month (campus_id, target_month),
    -- open_key가 NULL이면 = 비교는 "알 수 없음"이 되어 CHECK를 지나간다. NULL에도 참·거짓을 내는 <=>로 비교한다.
    CONSTRAINT ck_billing_invoice_open_key CHECK ((status = 'ISSUED' AND open_key <=> 1)
        OR (status <> 'ISSUED' AND open_key IS NULL)),
    CONSTRAINT ck_billing_invoice_total CHECK (total_amount >= 0)
) ENGINE = InnoDB;

-- 청구 라인. 반 하나에 한 줄. 8회 기준 수강료는 만든 순간의 값을 복사해 둔다(응답에는 싣지 않는다).
CREATE TABLE billing_invoice_line (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    invoice_id    BIGINT         NOT NULL,
    class_id      BIGINT         NOT NULL,
    lesson_count  INT            NOT NULL,
    base_fee_8    DECIMAL(15, 0) NOT NULL,
    -- 할인 없는 그 횟수의 금액(기준 수강료 × 횟수 ÷ 8, 1원 미만 버림)
    list_amount   DECIMAL(15, 0) NOT NULL,
    charge_amount DECIMAL(15, 0) NOT NULL,
    sort_order    INT            NOT NULL,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    created_by    BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_invoice_line_class (invoice_id, class_id),
    CONSTRAINT fk_billing_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES billing_invoice (id),
    CONSTRAINT ck_billing_invoice_line_lessons CHECK (lesson_count BETWEEN 1 AND 31),
    CONSTRAINT ck_billing_invoice_line_amounts CHECK (base_fee_8 > 0 AND charge_amount >= 0
        AND list_amount >= charge_amount)
) ENGINE = InnoDB;

-- 라인에 적용한 할인. amount는 할인을 순서대로 하나씩 더 적용했을 때 줄어든 금액이라,
-- 한 라인의 amount를 더하면 list_amount − charge_amount와 같다.
-- discount_ref는 할인을 넘긴 모듈의 참조값(예: 패스 번호). billing은 해석하지 않고 저장만 한다.
CREATE TABLE billing_invoice_line_discount (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    line_id            BIGINT         NOT NULL,
    discount_type_code VARCHAR(30)    NOT NULL,
    rate               DECIMAL(5, 4)  NOT NULL,
    amount             DECIMAL(15, 0) NOT NULL,
    discount_ref       VARCHAR(100),
    sort_order         INT            NOT NULL,
    created_at         DATETIME(6)    NOT NULL,
    updated_at         DATETIME(6)    NOT NULL,
    created_by         BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_invoice_line_discount_type (line_id, discount_type_code),
    CONSTRAINT fk_billing_invoice_line_discount_line FOREIGN KEY (line_id) REFERENCES billing_invoice_line (id),
    CONSTRAINT ck_billing_invoice_line_discount_values CHECK (rate > 0 AND rate < 1 AND amount >= 0)
) ENGINE = InnoDB;

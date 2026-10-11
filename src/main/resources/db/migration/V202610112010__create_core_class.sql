-- 반과 수강료표. 수강료는 8회 기준 금액이고 관리자만 보고 고친다.
-- 지우지 않고 active로 끈다. 청구 라인이 반을 참조한다.
CREATE TABLE core_class (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    campus_id  BIGINT         NOT NULL,
    name       VARCHAR(50)    NOT NULL,
    base_fee_8 DECIMAL(15, 0) NOT NULL,
    book_fee   DECIMAL(15, 0) NOT NULL,
    active     BOOLEAN        NOT NULL,
    created_at DATETIME(6)    NOT NULL,
    updated_at DATETIME(6)    NOT NULL,
    created_by BIGINT,
    PRIMARY KEY (id),
    -- 같은 지점에 같은 이름의 반을 두지 않는다. 같은 생성 요청이 두 번 와도 반이 두 개 생기지 않는다.
    UNIQUE KEY uk_core_class_campus_name (campus_id, name),
    CONSTRAINT fk_core_class_campus FOREIGN KEY (campus_id) REFERENCES core_campus (id)
) ENGINE = InnoDB;

-- 학부모. 전화번호 하나에 학부모는 한 명이다(ADR-0023).
-- 전화번호는 암호문과 검색용 인덱스로 저장한다(ADR-0009). 원문 컬럼은 없다.
CREATE TABLE core_guardian (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(50)  NOT NULL,
    phone_enc        VARCHAR(100) NOT NULL,
    phone_bidx       VARCHAR(64)  NOT NULL,
    phone_last4_bidx VARCHAR(64)  NOT NULL,
    key_version      INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    created_by       BIGINT,
    PRIMARY KEY (id),
    -- 같은 번호로 동시에 등록해도 학부모가 둘 생기지 않는다.
    UNIQUE KEY uk_core_guardian_phone (phone_bidx),
    KEY idx_core_guardian_last4 (phone_last4_bidx)
) ENGINE = InnoDB;

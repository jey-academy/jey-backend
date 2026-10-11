-- 할인 유형. 지점과 무관하게 학원 전체가 같이 쓴다. 어떤 할인을 적용할지는 등록할 때 직원이 고른다.
-- code는 청구에 남는 값이라 만든 뒤 바꾸지 않는다. 지우지 않고 active로 끈다.
CREATE TABLE core_discount_type (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    code       VARCHAR(30)   NOT NULL,
    name       VARCHAR(50)   NOT NULL,
    -- 할인율. 5%는 0.0500
    rate       DECIMAL(5, 4) NOT NULL,
    active     BOOLEAN       NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    created_by BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_discount_type_code (code)
) ENGINE = InnoDB;

-- 시각은 한국 시각으로 저장한다. NOW()는 DB 세션의 시간대를 따르므로 쓰지 않는다.
INSERT INTO core_discount_type (code, name, rate, active, created_at, updated_at)
VALUES ('SIBLING_5', '형제 할인 5%', 0.0500, TRUE,
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')),
       ('SIBLING_7', '형제 할인 7%', 0.0700, TRUE,
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')),
       ('DONGTAN_PASS', '동탄고 패스', 0.0500, TRUE,
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));

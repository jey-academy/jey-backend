-- 패스를 받는 지점과 그 지점이 주는 할인. 지금은 한 지점, 한 할인이다.
-- 할인율은 저장하지 않는다(할인 유형에서 읽는다). 지점과 할인 유형은 다른 모듈의 자료라 외래 키를 걸지 않는다.
CREATE TABLE dongtanpass_benefit (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    provider_campus_id BIGINT      NOT NULL,
    discount_type_code VARCHAR(30) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    created_by         BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_benefit (provider_campus_id, discount_type_code)
) ENGINE = InnoDB;

-- 시각은 한국 시각으로 저장한다. NOW()는 DB 세션의 시간대를 따르므로 쓰지 않는다.
INSERT INTO dongtanpass_benefit (provider_campus_id, discount_type_code, created_at, updated_at)
VALUES (1, 'DONGTAN_PASS', CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'),
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));

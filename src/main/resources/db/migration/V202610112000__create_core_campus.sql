-- 지점. 두 곳뿐이고 화면에서 만들거나 고치지 않는다.
CREATE TABLE core_campus (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_campus_name (name)
) ENGINE = InnoDB;

-- ID를 고정한다. 계정의 campus_id가 이 값을 가리킨다.
-- 시각은 한국 시각으로 저장한다. NOW()는 DB 세션의 시간대를 따르므로 쓰지 않는다.
INSERT INTO core_campus (id, name, created_at, updated_at)
VALUES (1, '동탄관', CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')),
       (2, '능동관', CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));

-- 계정을 만들 때는 지점 테이블이 없어 걸지 못했던 외래 키.
ALTER TABLE core_user
    ADD CONSTRAINT fk_core_user_campus FOREIGN KEY (campus_id) REFERENCES core_campus (id);

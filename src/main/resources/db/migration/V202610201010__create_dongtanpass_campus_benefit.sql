-- 반대 방향(JEY 발급 → 연계 학원 사용) 패스의 할인율. 한 줄만 둔다. 연계 학원과의 합의에 따라 관리자가 바꾼다.
-- 발급할 때 이 값을 패스(dongtanpass_pass.discount_rate)에 남긴다. 지점과 무관한 설정이라 campus_id가 없다.
CREATE TABLE dongtanpass_campus_benefit (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    discount_rate DECIMAL(5, 4) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    created_by    BIGINT,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO dongtanpass_campus_benefit (discount_rate, created_at, updated_at)
VALUES (0.0500, CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'), CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));

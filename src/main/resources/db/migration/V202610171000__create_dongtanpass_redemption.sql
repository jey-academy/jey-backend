-- 패스 사용 기록. 데스크가 등록된 학생에게 패스를 쓴 사실이다(ADR-0033). created_by가 처리한 직원이다.
-- 패스 하나와 학생 한 명은 한 번만 쓰인다(두 유니크 키). 사용 처리는 제약 이름으로 어느 쪽에 걸렸는지 가린다.
-- 학생은 다른 모듈의 자료라 외래 키를 걸지 않는다. campus_id는 쓴 지점이다(패스를 받는 지점).
-- 청구·결제를 가리키는 열은 수납 모듈과 이을 때 더한다.
CREATE TABLE dongtanpass_redemption (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    pass_id     BIGINT      NOT NULL,
    student_id  BIGINT      NOT NULL,
    campus_id   BIGINT      NOT NULL,
    redeemed_at DATETIME(6) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_redemption_pass (pass_id),
    UNIQUE KEY uk_dongtanpass_redemption_student (student_id),
    KEY ix_dongtanpass_redemption_campus_redeemed (campus_id, redeemed_at),
    CONSTRAINT fk_dongtanpass_redemption_pass FOREIGN KEY (pass_id) REFERENCES dongtanpass_pass (id)
) ENGINE = InnoDB;

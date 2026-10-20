-- 연계 학원이 JEY 발급 패스를 쓴 기록(반대 방향의 사용 처리). JEY 지점의 사용 기록(dongtanpass_redemption)과 모양이 달라 따로 둔다:
-- JEY 학생·지점 대신 연계 학원과 패스의 학생 ID, 그리고 금액(원래 수강료, 발급 때의 할인율, 할인액, 받을 금액)이 있다.
-- 패스 하나에 한 줄. 같은 연계 학원에서 같은 학생은 한 줄 — 할인은 그 학원에 새로 오는 학생에게만 준다(다른 학원이면 따로).
-- 사용 처리가 두 유니크 키의 이름으로 "이미 사용된 패스"와 "이 학원에서 이미 할인받은 학생"을 가린다.
CREATE TABLE dongtanpass_partner_redemption (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    pass_id         BIGINT         NOT NULL,
    partner_id      BIGINT         NOT NULL,
    student_id      BIGINT         NOT NULL,
    original_fee    DECIMAL(15, 0) NOT NULL,
    discount_rate   DECIMAL(5, 4)  NOT NULL,
    discount_amount DECIMAL(15, 0) NOT NULL,
    payable_amount  DECIMAL(15, 0) NOT NULL,
    redeemed_at     DATETIME(6)    NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    created_by      BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_partner_redemption_pass (pass_id),
    UNIQUE KEY uk_dongtanpass_partner_redemption_student (partner_id, student_id),
    KEY ix_dongtanpass_partner_redemption_partner_redeemed (partner_id, redeemed_at),
    KEY ix_dongtanpass_partner_redemption_redeemed (redeemed_at),
    CONSTRAINT fk_dongtanpass_partner_redemption_pass FOREIGN KEY (pass_id) REFERENCES dongtanpass_pass (id),
    CONSTRAINT fk_dongtanpass_partner_redemption_partner FOREIGN KEY (partner_id) REFERENCES dongtanpass_partner (id)
) ENGINE = InnoDB;

-- 패스의 방향. 지금까지의 패스는 연계 학원이 발급해 JEY 지점에서 쓴다(PARTNER_ISSUED).
-- 반대 방향(CAMPUS_ISSUED)은 JEY 지점이 다니는 학생에게 발급해 연계 학원에서 쓴다: 연계 학원이 없고,
-- 발급 지점·학생·발급 때의 할인율이 있다. 방향과 칸의 조합은 CHECK로 막는다.
-- 확장만 한다: blue/green 동안 옛 슬롯이 새 칸 없이 연계 학원 패스를 넣으므로 direction의 기본값을 남긴다.
-- issuer_campus_id·student_id는 다른 모듈의 ID라 외래 키를 걸지 않는다.
ALTER TABLE dongtanpass_pass
    ADD COLUMN direction VARCHAR(20) NOT NULL DEFAULT 'PARTNER_ISSUED' AFTER pass_no,
    MODIFY COLUMN partner_id BIGINT NULL,
    ADD COLUMN issuer_campus_id BIGINT NULL AFTER partner_id,
    ADD COLUMN student_id BIGINT NULL AFTER issuer_campus_id,
    ADD COLUMN discount_rate DECIMAL(5, 4) NULL AFTER payment_method,
    ADD KEY ix_dongtanpass_pass_campus_issued (issuer_campus_id, issued_at),
    ADD KEY ix_dongtanpass_pass_student (student_id),
    ADD CONSTRAINT ck_dongtanpass_pass_direction CHECK (
        (direction = 'PARTNER_ISSUED' AND partner_id IS NOT NULL AND issuer_campus_id IS NULL
            AND student_id IS NULL AND discount_rate IS NULL)
        OR (direction = 'CAMPUS_ISSUED' AND partner_id IS NULL AND issuer_campus_id IS NOT NULL
            AND student_id IS NOT NULL AND discount_rate IS NOT NULL));

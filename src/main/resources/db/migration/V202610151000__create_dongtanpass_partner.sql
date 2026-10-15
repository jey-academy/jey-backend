-- 연계 학원. 패스를 발급하는 학원이다(ADR-0034). 지점과 무관하다.
-- 계정(core_user.partner_id)이 이 행을 가리키지만 모듈이 달라 외래 키는 걸지 않는다.
CREATE TABLE dongtanpass_partner (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    type       VARCHAR(20) NOT NULL,
    active     BOOLEAN     NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_partner_name (name)
) ENGINE = InnoDB;

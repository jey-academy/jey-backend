-- 학부모의 동의를 연계 학원이 확인했다는 기록. 어느 고지 문구를 보여 줬는지 버전으로 남긴다(ADR-0035).
CREATE TABLE dongtanpass_consent (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    pass_id        BIGINT      NOT NULL,
    consent_type   VARCHAR(40) NOT NULL,
    notice_version VARCHAR(20) NOT NULL,
    channel        VARCHAR(30) NOT NULL,
    agreed_at      DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    created_by     BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_consent_pass_type (pass_id, consent_type),
    CONSTRAINT fk_dongtanpass_consent_pass FOREIGN KEY (pass_id) REFERENCES dongtanpass_pass (id)
) ENGINE = InnoDB;

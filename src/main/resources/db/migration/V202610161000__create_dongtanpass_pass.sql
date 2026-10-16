-- 동탄고 패스. 연계 학원이 결제를 확인하고 발급한다(ADR-0001). 지점과 무관하다(어느 지점에서 썼는지는 사용 기록이 가진다).
-- 학부모 번호는 전체 번호 색인(HMAC)만 둔다. 암호문이 없어 번호를 꺼낼 수 없다(ADR-0035). 색인의 키는 하나라 키 버전도 없다.
-- 만료 여부는 저장하지 않는다(만료일과 오늘로 계산한다). 결제 기록은 고치지 않는다.
CREATE TABLE dongtanpass_pass (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    pass_no             CHAR(9)        NOT NULL,
    -- 링크 토큰 원문의 SHA-256(소문자 16진수). 원문은 발급과 링크 새로 만들기의 응답에만 나간다
    link_token_hash     CHAR(64)       NOT NULL,
    partner_id          BIGINT         NOT NULL,
    student_name        VARCHAR(50)    NOT NULL,
    guardian_phone_bidx VARCHAR(64)    NOT NULL,
    issued_at           DATETIME(6)    NOT NULL,
    -- 발급일(한국 날짜)에 한 달을 더한 날. 그날까지 쓸 수 있다
    expires_on          DATE           NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    paid_on             DATE           NOT NULL,
    paid_amount         DECIMAL(15, 0) NOT NULL,
    payment_method      VARCHAR(20)    NOT NULL,
    version             BIGINT         NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    created_by          BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dongtanpass_pass_no (pass_no),
    UNIQUE KEY uk_dongtanpass_pass_link_token (link_token_hash),
    KEY ix_dongtanpass_pass_partner_issued (partner_id, issued_at),
    CONSTRAINT fk_dongtanpass_pass_partner FOREIGN KEY (partner_id) REFERENCES dongtanpass_partner (id)
) ENGINE = InnoDB;

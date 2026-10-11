-- 학생. 지우지 않고 status로 퇴원을 표시한다. 청구와 패스 사용 내역이 학생을 참조한다.
-- 본인 전화번호는 없을 수 있다. 있으면 네 컬럼이 모두 차고, 없으면 모두 NULL이다.
-- 학부모와 번호를 같이 쓰는 학생이 있어서 전화번호에 유니크 키를 두지 않는다.
CREATE TABLE core_student (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    campus_id        BIGINT       NOT NULL,
    class_id         BIGINT,
    name             VARCHAR(50)  NOT NULL,
    school           VARCHAR(50),
    grade            VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    registered_at    DATE         NOT NULL,
    phone_enc        VARCHAR(100),
    phone_bidx       VARCHAR(64),
    phone_last4_bidx VARCHAR(64),
    key_version      INT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    created_by       BIGINT,
    PRIMARY KEY (id),
    KEY idx_core_student_campus_name (campus_id, name),
    KEY idx_core_student_name (name),
    KEY idx_core_student_last4 (phone_last4_bidx),
    CONSTRAINT fk_core_student_campus FOREIGN KEY (campus_id) REFERENCES core_campus (id),
    CONSTRAINT fk_core_student_class FOREIGN KEY (class_id) REFERENCES core_class (id)
) ENGINE = InnoDB;

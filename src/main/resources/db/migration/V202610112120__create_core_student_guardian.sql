-- 학생과 학부모의 연결. 한 학부모가 여러 학생을, 한 학생이 여러 학부모를 가진다.
-- 형제는 따로 저장하지 않는다. 같은 학부모에 연결된 학생이 형제다(ADR-0023).
CREATE TABLE core_student_guardian (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    student_id  BIGINT      NOT NULL,
    guardian_id BIGINT      NOT NULL,
    relation    VARCHAR(20) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_student_guardian (student_id, guardian_id),
    KEY idx_core_student_guardian_guardian (guardian_id),
    CONSTRAINT fk_core_student_guardian_student FOREIGN KEY (student_id) REFERENCES core_student (id),
    CONSTRAINT fk_core_student_guardian_guardian FOREIGN KEY (guardian_id) REFERENCES core_guardian (id)
) ENGINE = InnoDB;

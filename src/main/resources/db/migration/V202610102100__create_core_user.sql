-- 로그인하는 계정(직원, 관리자, 연계 학원).
-- campus_id는 지점 테이블이 생길 때 외래 키를 건다.
CREATE TABLE core_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    campus_id     BIGINT,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    created_by    BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_user_login_id (login_id)
) ENGINE = InnoDB;

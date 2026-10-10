-- 연계 학원 계정이 어느 연계 학원의 것인지. 연계 학원 계정만 가진다.
-- 연계 학원 테이블은 동탄고 패스 모듈의 것이라 외래 키를 걸지 않는다.
ALTER TABLE core_user ADD COLUMN partner_id BIGINT NULL AFTER campus_id;

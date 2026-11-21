-- 학부모 정보의 버전. 이름·번호가 바뀔 때마다 오르고, 학부모 수정 요청이 화면이 받은 값을 다시 보내 낡은 저장을 막는다(ADR-0051).
ALTER TABLE core_guardian ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

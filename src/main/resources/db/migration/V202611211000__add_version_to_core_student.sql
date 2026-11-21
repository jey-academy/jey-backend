-- 학생 정보의 버전. 학생 행이 바뀔 때마다 오르고, 수정 요청이 화면이 받은 값을 다시 보내 낡은 저장을 막는다(ADR-0050).
ALTER TABLE core_student ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

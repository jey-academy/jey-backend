-- 학부모가 학생 이름과 번호로 링크를 다시 받을 때 번호 색인으로 패스를 찾는다(ADR-0035 개정).
-- 한 번호에 패스가 여럿일 수 있어 유니크가 아니다.
ALTER TABLE dongtanpass_pass ADD KEY ix_dongtanpass_pass_guardian_phone (guardian_phone_bidx);

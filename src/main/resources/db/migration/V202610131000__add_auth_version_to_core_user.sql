-- 권한 판 번호. 계정을 차단하거나 역할·지점·비밀번호를 바꿀 때마다 1씩 오른다.
-- 세션에는 로그인할 때의 번호가 들어 있고, 요청마다 이 값과 비교한다. 다르면 그 세션은 옛 계정의 것이다.
ALTER TABLE core_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 AFTER status;

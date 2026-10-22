-- 장부 탭 이름이 지점 이름을 담는 틀(예: 11월 동탄관(26))로 바뀌어 길어질 수 있다.
ALTER TABLE billing_ledger_sync MODIFY sheet_tab VARCHAR(100) NULL;

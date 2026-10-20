-- 패스 사용 기록에서 만든 청구(수납 모듈의 청구 ID). 사용 기록 하나에 열린 청구 하나다.
-- 청구를 취소하면 새 청구를 만들고 이 칸을 새 청구로 바꾼다(취소한 청구는 수납 모듈에 남는다).
-- 다른 모듈의 자료라 외래 키를 걸지 않는다. 확장만 한다: 옛 버전은 이 칸을 모르고 NULL로 둔다.
ALTER TABLE dongtanpass_redemption
    ADD COLUMN invoice_id BIGINT NULL AFTER redeemed_at,
    ADD UNIQUE KEY uk_dongtanpass_redemption_invoice (invoice_id);

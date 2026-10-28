-- 상품 이름을 "제이G패스"로 바꾼다(ADR-0049). code는 청구에 남는 값이라 그대로 둔다.
-- 청구는 이름을 코드로 찾아 읽으므로 지난 청구서도 새 이름으로 보인다.
-- 시각은 한국 시각으로 저장한다. NOW()는 DB 세션의 시간대를 따르므로 쓰지 않는다.
UPDATE core_discount_type
SET name       = '제이G패스',
    updated_at = CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')
WHERE code = 'DONGTAN_PASS';

package com.jey.core.campus.api;

import com.jey.core.shared.Money;

/**
 * 반의 수강료. 조회한 시점의 값이다. 청구는 만들 때 이 금액을 복사해 갖고, 나중에 수강료표가 바뀌어도 따라 바뀌지 않는다.
 *
 * @param baseFee8 8회 수업 기준 수강료
 * @param bookFee 교재비. 교재가 없으면 0원
 */
public record ClassFee(long classId, long campusId, Money baseFee8, Money bookFee) {
}

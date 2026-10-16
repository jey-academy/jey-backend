package com.jey.core.campus.api;

import java.util.Optional;

/**
 * 다른 모듈이 지점, 반, 수강료, 할인 유형을 확인하는 창구. 조회만 한다.
 *
 * <p>꺼진 반과 꺼진 할인 유형도 돌려준다. 이미 만든 청구가 그것을 참조하기 때문이다.
 * 새로 고르는 자리에서는 부르는 쪽이 {@code active}를 확인한다.
 */
public interface CampusCatalog {

	boolean campusExists(long campusId);

	/** 지점의 이름. 다른 모듈이 화면에 지점을 보여 줄 때 쓴다. */
	Optional<CampusInfo> findCampus(long campusId);

	Optional<ClassInfo> findClass(long classId);

	/**
	 * 반의 수강료. 청구액 계산에 쓴다.
	 * 수강료는 관리자만 본다. 이 값을 직원이나 연계 학원에게 가는 응답에 그대로 싣지 않는다.
	 */
	Optional<ClassFee> findClassFee(long classId);

	/**
	 * @param code 할인 유형 코드(예: {@code SIBLING_5})
	 */
	Optional<DiscountTypeInfo> findDiscountType(String code);

}

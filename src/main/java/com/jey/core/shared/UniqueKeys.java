package com.jey.core.shared;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 저장 실패가 어느 유니크 키 때문인지 가린다.
 * {@link DataIntegrityViolationException}은 유니크 키뿐 아니라 외래 키, NOT NULL, 길이 초과에서도 난다.
 * 그것까지 "이미 있습니다"로 답하면 다른 원인의 오류가 중복처럼 보인다.
 */
public final class UniqueKeys {

	private UniqueKeys() {
	}

	/**
	 * @param uniqueKeyName 마이그레이션에 적은 유니크 키 이름(예: {@code uk_core_class_campus_name})
	 */
	public static boolean isViolationOf(DataIntegrityViolationException ex, String uniqueKeyName) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			// MySQL은 키 이름을 "테이블.키" 형태로 준다.
			if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
					&& violation.getConstraintName().endsWith(uniqueKeyName)) {
				return true;
			}
		}
		return false;
	}

}

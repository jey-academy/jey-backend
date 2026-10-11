package com.jey.core.shared;

import java.util.Map;

/**
 * 스프링 없이 도는 테스트가 {@link ProtectedPhone}을 만들 때 쓴다.
 * {@link PhoneProtector}와 {@link ProtectedPhone}의 생성자는 이 패키지 밖에서 부를 수 없다.
 */
public final class TestPhones {

	private static final PhoneProtector PROTECTOR = new PhoneProtector(new PhoneCryptoProperties(1,
			Map.of(1, PhoneCryptoPropertiesTest.KEY_1), PhoneCryptoPropertiesTest.HMAC_KEY));

	private TestPhones() {
	}

	public static PhoneProtector protector() {
		return PROTECTOR;
	}

	public static ProtectedPhone protect(String phone) {
		return PROTECTOR.protect(PhoneNumber.of(phone));
	}

}

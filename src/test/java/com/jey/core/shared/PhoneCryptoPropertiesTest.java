package com.jey.core.shared;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PhoneCryptoPropertiesTest {

	static final String KEY_1 = base64("test-only-phone-enc-key-00000001");

	static final String KEY_2 = base64("test-only-phone-enc-key-00000002");

	static final String HMAC_KEY = base64("test-only-phone-hmac-key-0000001");

	@Test
	void 키가_맞으면_만들어진다() {
		assertThatCode(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), HMAC_KEY)).doesNotThrowAnyException();
		assertThatCode(() -> new PhoneCryptoProperties(2, Map.of(1, KEY_1, 2, KEY_2), HMAC_KEY))
				.doesNotThrowAnyException();
	}

	@Test
	void 암호화_키가_없으면_서버가_뜨지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(1, null, HMAC_KEY));
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(), HMAC_KEY));
	}

	// 새로 저장할 때 쓸 키가 없으면 첫 저장에서야 실패한다. 시작할 때 막는다.
	@Test
	void 지금_쓰는_버전의_키가_없으면_서버가_뜨지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(2, Map.of(1, KEY_1), HMAC_KEY))
				.withMessageContaining("active-key-version");
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(0, Map.of(1, KEY_1), HMAC_KEY));
	}

	@Test
	void 암호화_키는_Base64로_적은_32바이트여야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, base64("too-short")), HMAC_KEY));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, "base64가 아닌 값!!"), HMAC_KEY));
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, " "), HMAC_KEY));
		// 옛 버전의 키도 검사한다. 그 버전으로 저장된 값을 읽을 때에야 실패하면 늦다.
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(2, Map.of(1, base64("too-short"), 2, KEY_2), HMAC_KEY));
	}

	@Test
	void HMAC_키는_Base64로_적은_32바이트_이상이어야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), base64("too-short")));
		assertThatCode(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), base64("x".repeat(64))))
				.doesNotThrowAnyException();
	}

	// 환경변수가 없으면 "${...}"라는 글자가 그대로 들어온다. 그 글자를 키로 쓰면 누구나 아는 키가 된다.
	@Test
	void 환경변수가_채워지지_않은_키는_거부한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, "${PHONE_ENCRYPTION_KEY_1}"), HMAC_KEY))
				.withMessageContaining("환경변수");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), "${PHONE_HMAC_KEY}"))
				.withMessageContaining("환경변수");
	}

	// 한 키가 새면 암호문과 인덱스가 같이 뚫린다.
	@Test
	void HMAC_키는_암호화_키와_달라야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, KEY_1), KEY_1));
	}

	// 오류 문구와 toString은 로그에 남는다.
	@Test
	void 키는_오류_문구와_문자열_표현에_나오지_않는다() {
		String shortKey = base64("too-short-secret");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, shortKey), HMAC_KEY))
				.withMessageNotContaining(shortKey);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new PhoneCryptoProperties(1, Map.of(1, "not base64 secret!"), HMAC_KEY))
				.withMessageNotContaining("secret");
		assertThat(new PhoneCryptoProperties(1, Map.of(1, KEY_1), HMAC_KEY).toString()).doesNotContain(KEY_1)
				.doesNotContain(HMAC_KEY)
				.contains("activeKeyVersion=1");
	}

	static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes());
	}

}

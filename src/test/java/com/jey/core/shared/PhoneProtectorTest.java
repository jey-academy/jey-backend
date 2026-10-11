package com.jey.core.shared;

import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.jey.core.shared.PhoneCryptoPropertiesTest.HMAC_KEY;
import static com.jey.core.shared.PhoneCryptoPropertiesTest.KEY_1;
import static com.jey.core.shared.PhoneCryptoPropertiesTest.KEY_2;
import static com.jey.core.shared.PhoneCryptoPropertiesTest.base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PhoneProtectorTest {

	private static final PhoneNumber PHONE = PhoneNumber.of("010-1234-5678");

	private final PhoneProtector protector = new PhoneProtector(
			new PhoneCryptoProperties(1, Map.of(1, KEY_1), HMAC_KEY));

	@Test
	void 보호한_값을_되돌리면_원래_번호다() {
		ProtectedPhone stored = protector.protect(PHONE);

		assertThat(protector.reveal(stored)).isEqualTo(PHONE);
		assertThat(stored.getKeyVersion()).isEqualTo(1);
	}

	// 같은 번호가 같은 암호문이 되면, 암호문만 봐도 누가 같은 번호를 쓰는지 알 수 있다.
	@Test
	void 같은_번호도_보호할_때마다_암호문이_다르다() {
		Set<String> ciphertexts = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			ciphertexts.add(protector.protect(PHONE).getCiphertext());
		}

		assertThat(ciphertexts).hasSize(100);
	}

	@Test
	void 보호한_값_어디에도_원문_숫자가_없다() {
		ProtectedPhone stored = protector.protect(PHONE);
		String decoded = new String(Base64.getDecoder().decode(stored.getCiphertext()));

		assertThat(stored.getCiphertext() + stored.getFullIndex() + stored.getLast4Index() + decoded)
				.doesNotContain("01012345678")
				.doesNotContain("12345678");
		assertThat(stored.getFullIndex()).hasSize(64).matches("[0-9a-f]+");
		assertThat(stored.getLast4Index()).hasSize(64).matches("[0-9a-f]+");
		assertThat(stored.getCiphertext().length()).isLessThanOrEqualTo(ProtectedPhone.CIPHERTEXT_LENGTH);
	}

	// 로그에 객체를 그대로 찍어도 암호문과 인덱스가 남지 않는다.
	@Test
	void 보호한_값의_문자열_표현에는_키_버전만_있다() {
		ProtectedPhone stored = protector.protect(PHONE);

		assertThat(stored.toString()).isEqualTo("ProtectedPhone[keyVersion=1]");
	}

	// DB의 값이 바뀌었으면 엉뚱한 번호로 읽히지 않고 실패해야 한다.
	@Test
	void 암호문이_바뀌면_되돌리지_못한다() {
		ProtectedPhone stored = protector.protect(PHONE);
		byte[] bytes = Base64.getDecoder().decode(stored.getCiphertext());
		bytes[bytes.length - 1] ^= 1;
		ProtectedPhone tampered = new ProtectedPhone(Base64.getEncoder().encodeToString(bytes), stored.getFullIndex(),
				stored.getLast4Index(), stored.getKeyVersion());

		assertThatIllegalStateException().isThrownBy(() -> protector.reveal(tampered))
				.withMessageNotContaining(stored.getCiphertext())
				.withMessageNotContaining("1234");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "AAAA", "base64가 아닌 값" })
	void 암호문의_형식이_틀리면_되돌리지_못한다(String ciphertext) {
		ProtectedPhone broken = new ProtectedPhone(ciphertext, "a".repeat(64), "b".repeat(64), 1);

		assertThatIllegalStateException().isThrownBy(() -> protector.reveal(broken));
	}

	@Test
	void 다른_키로는_되돌리지_못한다() {
		ProtectedPhone stored = protector.protect(PHONE);
		PhoneProtector other = new PhoneProtector(new PhoneCryptoProperties(1, Map.of(1, KEY_2), HMAC_KEY));

		assertThatIllegalStateException().isThrownBy(() -> other.reveal(stored));
	}

	// 키를 교체해도 옛 키로 저장한 값을 읽을 수 있어야 한다. 새로 저장하는 값은 새 키를 쓴다.
	@Test
	void 키를_교체해도_옛_버전으로_저장한_값을_읽는다() {
		ProtectedPhone storedWithV1 = protector.protect(PHONE);
		PhoneProtector rotated = new PhoneProtector(
				new PhoneCryptoProperties(2, Map.of(1, KEY_1, 2, KEY_2), HMAC_KEY));

		assertThat(rotated.reveal(storedWithV1)).isEqualTo(PHONE);
		ProtectedPhone storedWithV2 = rotated.protect(PHONE);
		assertThat(storedWithV2.getKeyVersion()).isEqualTo(2);
		assertThat(rotated.reveal(storedWithV2)).isEqualTo(PHONE);
		// 인덱스는 HMAC 키로 만들므로 암호화 키를 바꿔도 그대로다. 교체 전후의 행이 같이 검색된다.
		assertThat(storedWithV2.getFullIndex()).isEqualTo(storedWithV1.getFullIndex());
		assertThat(storedWithV2.getLast4Index()).isEqualTo(storedWithV1.getLast4Index());
	}

	@Test
	void 설정에_없는_버전으로_저장한_값은_읽지_못한다() {
		PhoneProtector rotated = new PhoneProtector(
				new PhoneCryptoProperties(2, Map.of(1, KEY_1, 2, KEY_2), HMAC_KEY));
		ProtectedPhone storedWithV2 = rotated.protect(PHONE);

		assertThatIllegalStateException().isThrownBy(() -> protector.reveal(storedWithV2)).withMessageContaining("버전 2");
	}

	// 암호문을 다른 키 버전의 행에 옮겨 붙이는 것을 막는다. 같은 키를 두 버전에 넣은 설정에서도 실패해야 한다.
	@Test
	void 키_버전_표시를_바꾸면_되돌리지_못한다() {
		PhoneProtector sameKeyTwice = new PhoneProtector(
				new PhoneCryptoProperties(1, Map.of(1, KEY_1, 2, KEY_1), HMAC_KEY));
		ProtectedPhone stored = sameKeyTwice.protect(PHONE);
		ProtectedPhone relabeled = new ProtectedPhone(stored.getCiphertext(), stored.getFullIndex(),
				stored.getLast4Index(), 2);

		assertThatIllegalStateException().isThrownBy(() -> sameKeyTwice.reveal(relabeled));
	}

	// 저장할 때와 찾을 때 적는 방식이 달라도 같은 번호면 같은 인덱스여야 찾아진다.
	@Test
	void 같은_번호는_적는_방식이_달라도_인덱스가_같다() {
		ProtectedPhone stored = protector.protect(PhoneNumber.of("010-1234-5678"));

		assertThat(protector.fullIndex(PhoneNumber.of("+82 10 1234 5678"))).isEqualTo(stored.getFullIndex());
		assertThat(protector.protect(PhoneNumber.of("01012345678")).getFullIndex()).isEqualTo(stored.getFullIndex());
	}

	@Test
	void 뒷자리가_같은_두_번호는_뒷자리_인덱스가_같고_전체_인덱스는_다르다() {
		ProtectedPhone first = protector.protect(PhoneNumber.of("010-1234-5678"));
		ProtectedPhone second = protector.protect(PhoneNumber.of("010-9999-5678"));

		assertThat(second.getLast4Index()).isEqualTo(first.getLast4Index());
		assertThat(second.getFullIndex()).isNotEqualTo(first.getFullIndex());
		assertThat(protector.protect(PhoneNumber.of("010-1234-5679")).getLast4Index())
				.isNotEqualTo(first.getLast4Index());
	}

	// 데스크는 뒷자리 4자리만 입력한다. 그 4자리로 만든 인덱스가 저장된 인덱스와 같아야 찾아진다.
	@Test
	void 뒷자리만으로_만든_인덱스가_저장된_뒷자리_인덱스와_같다() {
		ProtectedPhone stored = protector.protect(PhoneNumber.of("010-0000-0078"));

		assertThat(protector.last4Index("0078")).isEqualTo(stored.getLast4Index());
		assertThat(protector.last4Index("0079")).isNotEqualTo(stored.getLast4Index());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "123", "12345", "12a4", "１２３４", " 1234" })
	void 뒷자리는_숫자_4자리여야_한다(String last4) {
		assertThatIllegalArgumentException().isThrownBy(() -> protector.last4Index(last4));
	}

	// 전체 번호의 인덱스와 뒷자리의 인덱스는 서로 다른 값 공간이다. 한쪽 값으로 다른 쪽을 찾을 수 없다.
	@Test
	void 전체_인덱스와_뒷자리_인덱스는_섞이지_않는다() {
		ProtectedPhone stored = protector.protect(PHONE);

		assertThat(stored.getFullIndex()).isNotEqualTo(stored.getLast4Index());
	}

	// 인덱스의 안전은 HMAC 키에 달려 있다. 키가 다르면 같은 번호도 다른 인덱스다.
	@Test
	void HMAC_키가_다르면_인덱스가_다르다() {
		PhoneProtector other = new PhoneProtector(
				new PhoneCryptoProperties(1, Map.of(1, KEY_1), base64("another-phone-hmac-key-000000001")));

		assertThat(other.fullIndex(PHONE)).isNotEqualTo(protector.fullIndex(PHONE));
		assertThat(other.last4Index("5678")).isNotEqualTo(protector.last4Index("5678"));
	}

}

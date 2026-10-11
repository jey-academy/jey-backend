package com.jey.core.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PhoneNumberTest {

	// 같은 번호를 어떻게 적든 한 가지 형태로 맞춘다. 저장할 때와 찾을 때 형태가 다르면 인덱스가 어긋난다.
	@ParameterizedTest
	@ValueSource(strings = { "01012345678", "010-1234-5678", "010 1234 5678", " 010-1234-5678 ", "010.1234.5678",
			"+82 10-1234-5678", "+821012345678", "+82 010-1234-5678" })
	void 적는_방식이_달라도_같은_번호로_맞춘다(String input) {
		assertThat(PhoneNumber.of(input).value()).isEqualTo("01012345678");
		assertThat(PhoneNumber.of(input)).isEqualTo(PhoneNumber.of("01012345678"));
	}

	@Test
	void 옛_번호_체계의_10자리_번호도_받는다() {
		assertThat(PhoneNumber.of("011-234-5678").value()).isEqualTo("0112345678");
		assertThat(PhoneNumber.of("016-1234-5678").value()).isEqualTo("01612345678");
	}

	// 알림과 패스 링크를 보낼 번호다. 유선 전화와 대표 번호는 받지 않는다.
	@ParameterizedTest
	@ValueSource(strings = { "02-123-4567", "031-123-4567", "1588-1234", "070-1234-5678", "0101234567", "010123456789",
			"010-1234-567a", "012-1234-5678", "010-12３4-5678", "8210-1234-5678", "+1 10-1234-5678", "++821012345678",
			"010-****-5678", "-" })
	void 휴대전화_번호가_아니면_만들_수_없다(String input) {
		assertThatIllegalArgumentException().isThrownBy(() -> PhoneNumber.of(input));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void 빈_값으로는_만들_수_없다(String input) {
		assertThatIllegalArgumentException().isThrownBy(() -> PhoneNumber.of(input));
	}

	// 틀린 입력도 누군가의 전화번호일 가능성이 크다. 예외 문구는 로그에 남는다.
	@Test
	void 예외_문구에_입력한_값을_싣지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> PhoneNumber.of("010-1234-56789"))
				.withMessageNotContaining("1234")
				.withMessageNotContaining("56789");
	}

	@Test
	void 뒷자리_4자리를_돌려준다() {
		assertThat(PhoneNumber.of("010-1234-5678").last4()).isEqualTo("5678");
		assertThat(PhoneNumber.of("011-234-0078").last4()).isEqualTo("0078");
	}

	@Test
	void 가린_표시는_가운데를_가리고_뒷자리만_보인다() {
		assertThat(PhoneNumber.of("01012345678").masked()).isEqualTo("010-****-5678");
		assertThat(PhoneNumber.of("0112345678").masked()).isEqualTo("011-***-5678");
	}

	// 로그에 객체를 그대로 찍어도 원문이 남지 않는다. 원문은 value()를 명시적으로 불러야 나온다.
	@Test
	void 문자열로_바꾸면_가린_표시가_나온다() {
		PhoneNumber phone = PhoneNumber.of("010-1234-5678");

		assertThat(phone.toString()).isEqualTo("010-****-5678");
		assertThat("학부모 연락처: " + phone).doesNotContain("1234");
	}

}

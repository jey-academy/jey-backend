package com.jey.core.member.web;

import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneRevealResponseTest {

	// 가운데 자리는 번호에 따라 세 자리이거나 네 자리다. 기대하는 표기를 글자 그대로 적어 본다.
	@Test
	void 가운데_자리의_길이에_맞춰_하이픈을_넣는다() {
		assertThat(PhoneRevealResponse.from(PhoneNumber.of("01012345678")).phone()).isEqualTo("010-1234-5678");
		assertThat(PhoneRevealResponse.from(PhoneNumber.of("0111234567")).phone()).isEqualTo("011-123-4567");
		assertThat(PhoneRevealResponse.from(PhoneNumber.of("01112345678")).phone()).isEqualTo("011-1234-5678");
	}

	// 응답 객체가 로그나 예외 문구에 실려도 번호가 나가지 않는다.
	@Test
	void 글로_바꾸면_번호가_드러나지_않는다() {
		PhoneRevealResponse response = PhoneRevealResponse.from(PhoneNumber.of("01012345678"));

		assertThat(response.toString()).doesNotContain("1234").doesNotContain("5678");
		assertThat("응답: " + response).doesNotContain("5678");
	}

}

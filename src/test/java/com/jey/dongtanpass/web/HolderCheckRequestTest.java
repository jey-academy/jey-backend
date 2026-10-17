package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 요청을 로그에 남기는 곳이 생겨도 학생 이름과 학부모 번호가 남지 않아야 한다.
class HolderCheckRequestTest {

	@Test
	void 문자열로_바꾸면_이름과_번호를_가린다() {
		String text = new HolderCheckRequest("김하윤", List.of(PhoneNumber.of("01098765432"))).toString();

		assertThat(text).doesNotContain("김하윤").doesNotContain("01098765432").doesNotContain("98765432");
	}

}

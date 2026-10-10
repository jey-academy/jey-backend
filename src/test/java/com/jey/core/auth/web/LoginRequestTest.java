package com.jey.core.auth.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestTest {

	// 요청 객체가 로그에 찍히더라도 값이 남지 않아야 한다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔해서 아이디도 남기지 않는다.
	@Test
	void 출력해도_아이디와_비밀번호는_나오지_않는다() {
		String printed = new LoginRequest("typed-id", "typed-password").toString();

		assertThat(printed).doesNotContain("typed-id").doesNotContain("typed-password");
	}

}

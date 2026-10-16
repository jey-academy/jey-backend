package com.jey.dongtanpass.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HolderTokenRequestTest {

	// 링크 토큰은 그 패스를 여는 열쇠다. 요청이 로그에 찍혀도 남지 않게 한다.
	@Test
	void 문자열로_바꿔도_토큰은_없다() {
		String token = "A".repeat(43);

		assertThat(new HolderTokenRequest(token).toString()).doesNotContain(token);
	}

}

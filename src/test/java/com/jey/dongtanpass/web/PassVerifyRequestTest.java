package com.jey.dongtanpass.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 요청을 로그에 남기는 곳이 생겨도 아직 쓰지 않은 QR 토큰이 로그에 남지 않아야 한다(로그를 읽는 사람이 60초 안에 쓸 수 있다).
class PassVerifyRequestTest {

	@Test
	void 문자열로_바꾸면_QR_토큰은_가리고_패스_번호는_보인다() {
		assertThat(new PassVerifyRequest("1.abcDEF.ghiJKL", null).toString()).doesNotContain("abcDEF")
			.doesNotContain("ghiJKL")
			.contains("***");
		assertThat(new PassVerifyRequest(null, "DT-ABC234").toString()).contains("DT-ABC234");
	}

}

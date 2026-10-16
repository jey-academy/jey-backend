package com.jey.dongtanpass.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PassIssuedTest {

	// 이벤트 이름은 감사 로그의 행위 이름이 되고, 미완료 발행 기록에도 적혀 있다. 바꾸지 않는다.
	@Test
	void 이벤트_이름과_대상의_종류는_정해져_있다() {
		assertThat(PassIssued.TYPE).isEqualTo("dongtanpass.pass-issued");
		assertThat(PassRefs.PASS).isEqualTo("pass");
		assertThat(PassRefs.pass(7)).satisfies(ref -> {
			assertThat(ref.type()).isEqualTo("pass");
			assertThat(ref.id()).isEqualTo(7);
		});
	}

}

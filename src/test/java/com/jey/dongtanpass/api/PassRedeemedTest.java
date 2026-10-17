package com.jey.dongtanpass.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PassRedeemedTest {

	// 이벤트 이름은 감사 로그의 행위 이름이 되고, 미완료 발행 기록에도 적혀 있다. 바꾸지 않는다.
	@Test
	void 이벤트_이름과_대상은_정해져_있다() {
		assertThat(PassRedeemed.TYPE).isEqualTo("dongtanpass.pass-redeemed");
		assertThat(PassRedeemed.VERSION).isEqualTo(1);
		assertThat(new PassRedeemed(null, 7, 8, 9).subject()).hasValueSatisfying(ref -> {
			assertThat(ref.type()).isEqualTo("pass");
			assertThat(ref.id()).isEqualTo(7);
		});
	}

}

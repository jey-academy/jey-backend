package com.jey.core.notification.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientTest {

	@Test
	void 받는_사람은_ID로_가리킨다() {
		assertThat(Recipient.guardian(12)).isEqualTo(new Recipient.Guardian(12));
		assertThat(Recipient.account(7)).isEqualTo(new Recipient.Account(7));
		assertThat(Recipient.admins()).isEqualTo(new Recipient.Admins());
	}

	// 같은 알림인지 가릴 때 받는 사람을 비교한다.
	@Test
	void 종류가_다르면_ID가_같아도_다른_사람이다() {
		assertThat(Recipient.guardian(7)).isNotEqualTo(Recipient.account(7));
		assertThat(Recipient.guardian(7)).isNotEqualTo(Recipient.guardian(8));
		assertThat(Recipient.admins()).isEqualTo(Recipient.admins());
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1, Long.MIN_VALUE })
	void ID는_양수여야_한다(long id) {
		assertThatThrownBy(() -> Recipient.guardian(id)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Recipient.account(id)).isInstanceOf(IllegalArgumentException.class);
	}

	// 로그에 그대로 찍는다. 종류와 숫자 ID만 나온다.
	@Test
	void 글로_적으면_종류와_ID만_나온다() {
		assertThat(Recipient.guardian(12)).hasToString("guardian:12");
		assertThat(Recipient.account(7)).hasToString("account:7");
		assertThat(Recipient.admins()).hasToString("admins");
	}

}

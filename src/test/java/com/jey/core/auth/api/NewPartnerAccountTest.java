package com.jey.core.auth.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

// 연계 학원 테이블은 다른 모듈에 있어 계정의 연계 학원 식별자에는 외래 키가 없다. 값이 될 수 없는 식별자는 여기서 막는다.
class NewPartnerAccountTest {

	@ParameterizedTest
	@ValueSource(longs = { 0, -1 })
	void 양수가_아닌_연계_학원_식별자는_받지_않는다(long partnerId) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new NewPartnerAccount(partnerId, "sunshine", "햇살수학", "partner-password-123"));
	}

	@Test
	void 빠진_값은_받지_않는다() {
		assertThatNullPointerException().isThrownBy(() -> new NewPartnerAccount(1, null, "햇살수학", "password-123"));
		assertThatNullPointerException().isThrownBy(() -> new NewPartnerAccount(1, "sunshine", null, "password-123"));
		assertThatNullPointerException().isThrownBy(() -> new NewPartnerAccount(1, "sunshine", "햇살수학", null));
	}

	@Test
	void 로그에_찍어도_비밀번호는_나오지_않는다() {
		assertThat(new NewPartnerAccount(1, "sunshine", "햇살수학", "partner-password-123").toString())
			.contains("sunshine")
			.doesNotContain("partner-password-123");
	}

}

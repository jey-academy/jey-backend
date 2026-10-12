package com.jey.core.auth.web;

import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 비밀번호가 든 요청을 로그에 찍는 곳은 지금 없다. 누가 찍게 되더라도(디버그 로그, 예외 문구) 값은 나오지 않아야 한다.
class PasswordRequestTest {

	private static final String PASSWORD = "secret-password-123";

	@Test
	void 계정_만들기_요청을_글로_적어도_비밀번호는_나오지_않는다() {
		var request = new AccountCreateRequest("desk", "김직원", PASSWORD, UserRole.STAFF, 1L);

		assertThat(request.toString()).contains("desk").doesNotContain(PASSWORD);
		assertThat(request.toNewAccount().toString()).doesNotContain(PASSWORD);
		assertThat(request.toNewAccount().password()).isEqualTo(PASSWORD);
	}

	@Test
	void 비밀번호_다시_정하기_요청을_글로_적어도_비밀번호는_나오지_않는다() {
		assertThat(new PasswordResetRequest(PASSWORD).toString()).doesNotContain(PASSWORD);
	}

	@Test
	void 비밀번호_변경_요청을_글로_적어도_지금_것도_새것도_나오지_않는다() {
		var request = new PasswordChangeRequest(PASSWORD, "another-secret-456");

		assertThat(request.toString()).doesNotContain(PASSWORD).doesNotContain("another-secret-456");
	}

}

package com.jey.core.auth;

import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AccountInputTest {

	// 지금은 이 값을 로그에 찍는 곳이 없다. 누가 찍게 되더라도 비밀번호는 나오지 않아야 한다.
	@Test
	void 만들_계정을_글로_적어도_비밀번호는_나오지_않는다() {
		NewAccount account = new NewAccount("desk", "김직원", "desk-password-123", UserRole.STAFF, 1L);

		assertThat(account.toString()).contains("desk").contains("STAFF").doesNotContain("desk-password-123");
		assertThat(String.valueOf(account)).doesNotContain("password-123");
	}

	// 빠진 값은 만들 때 막는다. 빠진 채로 서비스에 가면 뜻 모를 자리에서 실패한다.
	@Test
	void 빠진_값이_있으면_만들_수_없다() {
		assertThatNullPointerException()
			.isThrownBy(() -> new NewAccount(null, "김직원", "desk-password-123", UserRole.STAFF, 1L))
			.withMessage("loginId");
		assertThatNullPointerException()
			.isThrownBy(() -> new NewAccount("desk", null, "desk-password-123", UserRole.STAFF, 1L))
			.withMessage("name");
		assertThatNullPointerException().isThrownBy(() -> new NewAccount("desk", "김직원", null, UserRole.STAFF, 1L))
			.withMessage("password");
		assertThatNullPointerException()
			.isThrownBy(() -> new NewAccount("desk", "김직원", "desk-password-123", null, 1L))
			.withMessage("role");
		assertThatNullPointerException().isThrownBy(() -> new AccountChange(null, UserRole.STAFF, 1L))
			.withMessage("name");
		assertThatNullPointerException().isThrownBy(() -> new AccountChange("김직원", null, 1L)).withMessage("role");
	}

	// 지점은 없을 수 있다(관리자).
	@Test
	void 지점은_비울_수_있다() {
		assertThat(new NewAccount("owner", "박원장", "owner-password-123", UserRole.ADMIN, null).campusId()).isNull();
		assertThat(new AccountChange("박원장", UserRole.ADMIN, null).campusId()).isNull();
	}

}

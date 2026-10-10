package com.jey.core.auth.domain;

import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UserTest {

	private static final String HASH = "{noop}not-a-real-hash";

	@Test
	void 아이디는_앞뒤_공백을_떼고_소문자로_저장한다() {
		User user = User.create("  Desk-Staff ", HASH, "김직원", UserRole.STAFF, 1L);

		assertThat(user.getLoginId()).isEqualTo("desk-staff");
		assertThat(User.normalizeLoginId(" ADMIN ")).isEqualTo("admin");
	}

	// 지점 없는 직원을 허용하면 "지점이 없으면 전 지점"으로 해석하는 곳에서 전 지점 권한이 된다.
	@Test
	void 직원은_소속_지점이_있어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", HASH, "김직원", UserRole.STAFF, null));
	}

	@Test
	void 관리자와_연계_학원은_소속_지점을_가질_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("admin", HASH, "박원장", UserRole.ADMIN, 1L));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("partner", HASH, "연계학원", UserRole.PARTNER, 1L));
		assertThat(User.create("admin", HASH, "박원장", UserRole.ADMIN, null).getCampusId()).isNull();
	}

	// 인코더가 만든 해시는 {bcrypt}처럼 알고리즘 이름으로 시작한다. 원문 비밀번호를 그대로 넘기는 실수를 막는다.
	@Test
	void 해시되지_않은_비밀번호로는_만들_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", "plain-password", "김직원", UserRole.STAFF, 1L))
				.withMessageContaining("passwordHash");
	}

	// DB 컬럼 길이를 넘는 값이 DB 오류(500)로 드러나지 않게 미리 막는다.
	@Test
	void 아이디와_이름은_50자를_넘을_수_없다() {
		assertThat(User.create("a".repeat(50), HASH, "가".repeat(50), UserRole.STAFF, 1L).getLoginId()).hasSize(50);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("a".repeat(51), HASH, "김직원", UserRole.STAFF, 1L));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", HASH, "가".repeat(51), UserRole.STAFF, 1L));
	}

	@Test
	void 필수값이_비면_계정을_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> User.create(" ", HASH, "김직원", UserRole.STAFF, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", " ", "김직원", UserRole.STAFF, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", HASH, " ", UserRole.STAFF, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", HASH, "김직원", null, 1L));
	}

	@Test
	void 차단하면_비활성이_된다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L);
		assertThat(user.isActive()).isTrue();

		user.disable();

		assertThat(user.isActive()).isFalse();
	}

}

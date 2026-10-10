package com.jey.core.auth.domain;

import java.util.Locale;

import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

	// DB는 악센트가 붙은 글자나 전각 문자를 같은 글자로 본다. 그런 문자를 허용하면 서로 다른 입력이 한 계정에 닿는다.
	@ParameterizedTest
	@ValueSource(strings = { "ádmin", "ａdmin", "관리자", "desk staff", "desk　staff", "ab", "-desk", ".desk",
			"desk@jey", "DESK İ" })
	void 아이디에는_영문_소문자와_숫자와_일부_기호만_쓸_수_있다(String loginId) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L))
				.withMessageContaining("loginId");
		assertThat(User.isValidLoginId(User.normalizeLoginId(loginId))).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "desk-staff", "desk_staff", "desk.staff", "staff01", "1desk" })
	void 허용하는_아이디_형식(String loginId) {
		assertThat(User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L).getLoginId()).isEqualTo(loginId);
	}

	// 기본 로케일이 터키어여도 대문자 I가 영문 소문자 i가 돼야 한다(터키어에서는 점 없는 ı가 된다).
	@Test
	void 소문자로_바꿀_때_실행_환경의_언어_설정에_영향받지_않는다() {
		Locale original = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr"));
		try {
			assertThat(User.normalizeLoginId("ADMIN")).isEqualTo("admin");
		}
		finally {
			Locale.setDefault(original);
		}
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

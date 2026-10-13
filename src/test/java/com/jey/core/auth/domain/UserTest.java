package com.jey.core.auth.domain;

import java.util.Locale;

import com.jey.core.auth.api.AuthenticatedUser;
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
		User user = User.create("  Desk-Staff ", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThat(user.getLoginId()).isEqualTo("desk-staff");
		assertThat(User.normalizeLoginId(" ADMIN ")).isEqualTo("admin");
	}

	// DB는 악센트가 붙은 글자나 전각 문자를 같은 글자로 본다. 그런 문자를 허용하면 서로 다른 입력이 한 계정에 닿는다.
	@ParameterizedTest
	@ValueSource(strings = { "ádmin", "ａdmin", "관리자", "desk staff", "desk　staff", "ab", "-desk", ".desk",
			"desk@jey", "DESK İ" })
	void 아이디에는_영문_소문자와_숫자와_일부_기호만_쓸_수_있다(String loginId) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L, null))
				.withMessageContaining("loginId");
		assertThat(User.isValidLoginId(User.normalizeLoginId(loginId))).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "desk-staff", "desk_staff", "desk.staff", "staff01", "1desk" })
	void 허용하는_아이디_형식(String loginId) {
		assertThat(User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L, null).getLoginId()).isEqualTo(loginId);
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
				.isThrownBy(() -> User.create("staff", HASH, "김직원", UserRole.STAFF, null, null));
	}

	@Test
	void 관리자와_연계_학원은_소속_지점을_가질_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("admin", HASH, "박원장", UserRole.ADMIN, 1L, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("partner", HASH, "연계학원", UserRole.PARTNER, 1L, 7L));
		assertThat(User.create("admin", HASH, "박원장", UserRole.ADMIN, null, null).getCampusId()).isNull();
	}

	// 인코더가 만든 해시는 {bcrypt}처럼 알고리즘 이름으로 시작한다. 원문 비밀번호를 그대로 넘기는 실수를 막는다.
	@Test
	void 해시되지_않은_비밀번호로는_만들_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", "plain-password", "김직원", UserRole.STAFF, 1L, null))
				.withMessageContaining("passwordHash");
	}

	// DB 컬럼 길이를 넘는 값이 DB 오류(500)로 드러나지 않게 미리 막는다.
	@Test
	void 아이디와_이름은_50자를_넘을_수_없다() {
		assertThat(User.create("a".repeat(50), HASH, "가".repeat(50), UserRole.STAFF, 1L, null).getLoginId()).hasSize(50);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("a".repeat(51), HASH, "김직원", UserRole.STAFF, 1L, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", HASH, "가".repeat(51), UserRole.STAFF, 1L, null));
	}

	@Test
	void 필수값이_비면_계정을_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> User.create(" ", HASH, "김직원", UserRole.STAFF, 1L, null));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", " ", "김직원", UserRole.STAFF, 1L, null));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", HASH, " ", UserRole.STAFF, 1L, null));
		assertThatIllegalArgumentException().isThrownBy(() -> User.create("staff", HASH, "김직원", null, 1L, null));
	}

	@Test
	void 차단하면_비활성이_된다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		assertThat(user.isActive()).isTrue();

		user.disable();

		assertThat(user.isActive()).isFalse();
	}

	@Test
	void 차단을_풀면_다시_활성이_된다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		user.disable();

		user.enable();

		assertThat(user.isActive()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
	}

	@Test
	void 이름을_고치면_바뀌었는지_알려_준다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThat(user.rename("김데스크")).isTrue();
		assertThat(user.getName()).isEqualTo("김데스크");
		assertThat(user.rename("김데스크")).isFalse();
	}

	@Test
	void 이름은_비우거나_50자를_넘길_수_없다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThatIllegalArgumentException().isThrownBy(() -> user.rename(" "));
		assertThatIllegalArgumentException().isThrownBy(() -> user.rename(null));
		assertThatIllegalArgumentException().isThrownBy(() -> user.rename("가".repeat(51)));
		assertThat(user.rename("가".repeat(50))).isTrue();
	}

	@Test
	void 역할과_지점을_바꾸면_바뀌었는지_알려_준다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThat(user.changeAffiliation(UserRole.STAFF, 1L)).isFalse();
		assertThat(user.changeAffiliation(UserRole.STAFF, 2L)).isTrue();
		assertThat(user.getCampusId()).isEqualTo(2L);
		assertThat(user.changeAffiliation(UserRole.ADMIN, null)).isTrue();
		assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(user.getCampusId()).isNull();
	}

	// 지점 없는 직원은 "지점이 없으면 전 지점"으로 해석하는 곳에서 전 지점 권한이 된다. 고칠 때도 만들 때와 같은 검사를 거친다.
	@Test
	void 역할과_지점의_조합이_맞지_않으면_바꿀_수_없고_값은_그대로다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThatIllegalArgumentException().isThrownBy(() -> user.changeAffiliation(UserRole.STAFF, null));
		assertThatIllegalArgumentException().isThrownBy(() -> user.changeAffiliation(UserRole.ADMIN, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> user.changeAffiliation(UserRole.PARTNER, null));
		assertThatIllegalArgumentException().isThrownBy(() -> user.changeAffiliation(null, 1L));
		assertThat(user.getRole()).isEqualTo(UserRole.STAFF);
		assertThat(user.getCampusId()).isEqualTo(1L);
	}

	// 연계 학원 식별자를 가진 계정은 다른 역할이 될 수 없다(그 역할은 식별자를 가질 수 없다).
	@Test
	void 연계_학원_계정은_다른_역할로_바꿀_수_없다() {
		User partner = User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L);

		assertThatIllegalArgumentException().isThrownBy(() -> partner.changeAffiliation(UserRole.STAFF, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> partner.changeAffiliation(UserRole.ADMIN, null));
		assertThat(partner.changeAffiliation(UserRole.PARTNER, null)).isFalse();
	}

	@Test
	void 비밀번호는_해시된_값으로만_바꿀_수_있다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThatIllegalArgumentException().isThrownBy(() -> user.changePassword("plain-password"));
		assertThatIllegalArgumentException().isThrownBy(() -> user.changePassword(" "));
		assertThat(user.getPasswordHash()).isEqualTo(HASH);

		user.changePassword("{noop}another-hash");

		assertThat(user.getPasswordHash()).isEqualTo("{noop}another-hash");
	}

	// 식별자가 빈 연계 학원 계정은 "자기 발급분" 조건을 걸 수 없다.
	@Test
	void 연계_학원_계정은_연계_학원_식별자가_있어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, null))
				.withMessageContaining("연계 학원 식별자");
		assertThat(User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L).getPartnerId()).isEqualTo(7L);
	}

	@Test
	void 관리자와_직원은_연계_학원_식별자를_가질_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("admin", HASH, "박원장", UserRole.ADMIN, null, 7L));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, 7L));
		assertThat(User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null).getPartnerId()).isNull();
	}

	// 권한 판 번호 (ADR-0031). 이 번호가 오르면 그 전에 만든 세션은 다음 요청에서 막힌다.
	@Test
	void 차단하거나_역할_지점_비밀번호를_바꾸면_권한_판_번호가_오른다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		assertThat(user.getAuthVersion()).isZero();

		user.changeAffiliation(UserRole.STAFF, 2L);
		assertThat(user.getAuthVersion()).as("지점이 바뀜").isEqualTo(1);

		user.changeAffiliation(UserRole.ADMIN, null);
		assertThat(user.getAuthVersion()).as("역할이 바뀜").isEqualTo(2);

		user.changePassword("{noop}another-hash");
		assertThat(user.getAuthVersion()).as("비밀번호가 바뀜").isEqualTo(3);

		user.disable();
		assertThat(user.getAuthVersion()).as("차단됨").isEqualTo(4);
	}

	@Test
	void 세션에_든_것이_달라지지_않는_변경은_권한_판_번호를_올리지_않는다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		user.rename("김데스크");
		user.changeAffiliation(UserRole.STAFF, 1L);
		assertThatIllegalArgumentException().isThrownBy(() -> user.changeAffiliation(UserRole.ADMIN, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> user.changePassword("plain-password"));

		assertThat(user.getAuthVersion()).isZero();
	}

	// 차단할 때 번호가 올랐으므로 차단 전의 세션은 풀어도 살아나지 않는다. 풀 때 또 올릴 필요가 없다.
	@Test
	void 차단을_풀_때는_번호를_올리지_않고_이미_차단된_계정을_다시_차단해도_오르지_않는다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		user.disable();
		user.disable();
		user.enable();

		assertThat(user.getAuthVersion()).isEqualTo(1);
	}

	@Test
	void 로그인할_때와_같은_계정이면_그_세션은_유효하다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);

		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), 0)).isTrue();
		user.rename("김데스크");
		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), 0)).as("이름은 권한이 아니다").isTrue();
	}

	@Test
	void 차단된_계정의_세션은_유효하지_않다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		user.disable();

		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), user.getAuthVersion())).isFalse();
	}

	@Test
	void 권한_판_번호가_다른_세션은_유효하지_않다() {
		User user = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		user.changePassword("{noop}another-hash");

		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), 0)).as("옛 번호").isFalse();
		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), 1)).as("지금 번호").isTrue();
		assertThat(user.stillValidFor(sessionUser(UserRole.STAFF, 1L, null), 2)).as("앞선 번호").isFalse();
	}

	// 번호가 같아도 역할·지점·연계 학원 식별자가 다르면 막는다. DB를 손으로 고쳐 번호가 오르지 않은 경우다.
	@Test
	void 번호가_같아도_역할이나_소속이_세션과_다르면_유효하지_않다() {
		User staff = User.create("staff", HASH, "김직원", UserRole.STAFF, 1L, null);
		User partner = User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L);

		assertThat(staff.stillValidFor(sessionUser(UserRole.ADMIN, null, null), 0)).as("역할").isFalse();
		assertThat(staff.stillValidFor(sessionUser(UserRole.STAFF, 2L, null), 0)).as("지점").isFalse();
		assertThat(partner.stillValidFor(sessionUser(UserRole.PARTNER, null, 8L), 0)).as("연계 학원 식별자").isFalse();
		assertThat(partner.stillValidFor(sessionUser(UserRole.PARTNER, null, 7L), 0)).isTrue();
	}

	private static AuthenticatedUser sessionUser(UserRole role, Long campusId, Long partnerId) {
		return new AuthenticatedUser(42L, "login", "이름", role, campusId, partnerId);
	}

}

package com.jey.core.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AccountUserDetailsTest {

	private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuv";

	@Test
	void 역할은_ROLE_접두사를_붙인_권한이_된다() {
		var details = new AccountUserDetails(savedUser("desk"));

		assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_STAFF");
		assertThat(details.getUsername()).isEqualTo("desk");
		assertThat(details.getPassword()).isEqualTo(HASH);
	}

	// Spring Security는 isEnabled를 비밀번호보다 먼저 확인한다. 여기서 false를 주면 비활성 계정만 해시 계산 없이 빨리 실패해서
	// 응답 시간으로 가려낼 수 있다. 비활성 여부는 비밀번호가 맞은 뒤에 LoginService가 DB에서 다시 읽어 확인한다.
	@Test
	void 비활성_계정도_비밀번호_검증까지는_간다() {
		User disabled = savedUser("left");
		disabled.disable();

		var details = new AccountUserDetails(disabled);

		assertThat(details.isEnabled()).isTrue();
	}

	@Test
	void 검증이_끝나면_해시를_지운다() {
		var details = new AccountUserDetails(savedUser("desk"));

		details.eraseCredentials();

		assertThat(details.getPassword()).isNull();
	}

	// 로그인은 세션을 만들기 직전에 계정을 다시 읽어 이것으로 비교한다. 비교에서 빠진 값이 있으면,
	// 그 값이 바뀐 계정의 로그인이 옛 권한이나 옛 비밀번호의 세션으로 살아남는다.
	@Test
	void 다시_읽은_계정의_권한과_비밀번호가_그대로인지_가린다() {
		var staff = new AccountUserDetails(savedUser("desk"));

		assertThat(staff.isSameAs(savedUser("desk"))).isTrue();
		User renamed = savedUser("desk");
		renamed.rename("김데스크");
		assertThat(staff.isSameAs(renamed)).as("이름은 권한이 아니다").isTrue();

		User moved = savedUser("desk");
		moved.changeAffiliation(UserRole.STAFF, 2L);
		assertThat(staff.isSameAs(moved)).as("지점이 바뀜").isFalse();

		User promoted = savedUser("desk");
		promoted.changeAffiliation(UserRole.ADMIN, null);
		assertThat(staff.isSameAs(promoted)).as("역할이 바뀜").isFalse();

		User reset = savedUser("desk");
		reset.changePassword("{bcrypt}$2a$10$zyxwvutsrqponmlkjihgfe");
		assertThat(staff.isSameAs(reset)).as("비밀번호가 바뀜").isFalse();
	}

	@Test
	void 관리자에서_직원으로_내려간_계정은_같은_계정으로_보지_않는다() {
		var details = new AccountUserDetails(saved(User.create("owner", HASH, "박원장", UserRole.ADMIN, null, null)));
		User demoted = saved(User.create("owner", HASH, "박원장", UserRole.ADMIN, null, null));
		demoted.changeAffiliation(UserRole.STAFF, 1L);

		assertThat(details.isSameAs(demoted)).isFalse();
	}

	@Test
	void 연계_학원_식별자가_바뀐_계정은_같은_계정으로_보지_않는다() {
		var details = new AccountUserDetails(
				saved(User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L)));

		assertThat(details.isSameAs(saved(User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L))))
			.isTrue();
		assertThat(details.isSameAs(saved(User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 8L))))
			.isFalse();
	}

	// 해시는 검증이 끝나면 지운다. 그 뒤에도 "그때의 해시가 지금도 그대로인가"를 가릴 수 있어야 한다.
	@Test
	void 해시를_지운_뒤에도_비밀번호가_바뀌었는지_가린다() {
		var details = new AccountUserDetails(savedUser("desk"));
		details.eraseCredentials();
		User reset = savedUser("desk");
		reset.changePassword("{bcrypt}$2a$10$zyxwvutsrqponmlkjihgfe");

		assertThat(details.isSameAs(savedUser("desk"))).isTrue();
		assertThat(details.isSameAs(reset)).isFalse();
	}

	// DB에서 읽은 계정처럼 ID가 채워진 계정을 만든다.
	private static User savedUser(String loginId) {
		return saved(User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L, null));
	}

	private static User saved(User user) {
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	// 실수로 세션에 들어가더라도 해시가 Redis에 남지 않게 한다.
	@Test
	void 직렬화해도_해시는_들어가지_않는다() throws Exception {
		var details = new AccountUserDetails(savedUser("desk"));
		var bytes = new ByteArrayOutputStream();
		try (var out = new ObjectOutputStream(bytes)) {
			out.writeObject(details);
		}

		assertThat(bytes.toString("ISO-8859-1")).contains("desk").doesNotContain("$2a$10$");
		try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThat(((AccountUserDetails) in.readObject()).getPassword()).isNull();
		}
	}

	@Test
	void 연계_학원_식별자가_로그인_정보로_넘어간다() {
		User partner = User.create("partner", HASH, "연계학원", UserRole.PARTNER, null, 7L);
		ReflectionTestUtils.setField(partner, "id", 8L);

		assertThat(new AccountUserDetails(partner).toAuthenticatedUser().partnerId()).isEqualTo(7L);
	}

}

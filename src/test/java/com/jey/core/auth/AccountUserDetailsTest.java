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

	// DB에서 읽은 계정처럼 ID가 채워진 계정을 만든다.
	private static User savedUser(String loginId) {
		User user = User.create(loginId, HASH, "김직원", UserRole.STAFF, 1L, null);
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

package com.jey.core.auth.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AuthenticatedUserTest {

	// Spring Security와 Spring Session은 이 이름으로 "누구의 세션인가"를 가린다.
	// 계정 ID여야 로그에 아이디·실명이 찍히지 않고, 계정 ID로 세션을 찾아 끊을 수 있다.
	@Test
	void 로그인_정보의_이름은_계정_ID다() {
		var user = new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L);

		assertThat(user.getName()).isEqualTo("42");
		assertThat(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()).getName()).isEqualTo("42");
	}

	@Test
	void 직원은_소속_지점이_있어야_하고_그_밖의_역할은_지점이_없어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "desk", "김직원", UserRole.STAFF, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "admin", "박원장", UserRole.ADMIN, 1L));
		assertThat(new AuthenticatedUser(1L, "admin", "박원장", UserRole.ADMIN, null).campusId()).isNull();
	}

	@Test
	void 필수값이_비면_만들_수_없다() {
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(null, "desk", "김직원", UserRole.STAFF, 1L))
				.withMessage("id");
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, null, "김직원", UserRole.STAFF, 1L))
				.withMessage("loginId");
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "desk", null, UserRole.STAFF, 1L))
				.withMessage("displayName");
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "desk", "김직원", null, 1L))
				.withMessage("role");
	}

	// 세션은 Java 직렬화로 Redis에 저장된다.
	@Test
	void 직렬화했다가_되살리면_같은_값이다() throws Exception {
		var user = new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L);
		var bytes = new ByteArrayOutputStream();
		try (var out = new ObjectOutputStream(bytes)) {
			out.writeObject(user);
		}

		Object restored;
		try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			restored = in.readObject();
		}

		assertThat(restored).isEqualTo(user);
	}

}

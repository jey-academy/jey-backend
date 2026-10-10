package com.jey.core.auth;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// LoginServiceTest는 인증 관리자를 가짜로 바꿔서 본다. 여기서는 Spring Security의 실제 인증 관리자와
// 실제 계정 조회 서비스를 이어 붙여서, 저장소에서 난 일이 LoginService까지 어떻게 전달되는지 확인한다.
class LoginWiringTest {

	private static final String PASSWORD = "correct-horse-battery-staple";

	private final UserRepository users = mock(UserRepository.class);

	private final CountingPasswordEncoder passwordEncoder = new CountingPasswordEncoder();

	private final LoginService loginService = newLoginService();

	// DB가 내려가면 Spring Security가 그 예외를 인증 예외로 감싸서 돌려준다. 그것이 "비밀번호가 틀렸다"가 되면 안 된다.
	@Test
	void 저장소에서_난_DB_오류는_자격_증명_오류가_되지_않는다() {
		var dbDown = new DataAccessResourceFailureException("DB down");
		when(users.findByLoginId(anyString())).thenThrow(dbDown);

		assertThatThrownBy(() -> login("desk", PASSWORD))
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(BusinessException.class)
				.hasRootCause(dbDown);
	}

	// 비활성 계정만 해시 계산 없이 빨리 실패하면 응답 시간으로 가려낼 수 있다. 비활성 계정도 해시를 한 번 계산해야 한다.
	@Test
	void 비활성_계정도_비밀번호_해시를_한_번_계산한다() {
		User disabled = savedUser();
		disabled.disable();
		when(users.findByLoginId("desk")).thenReturn(Optional.of(disabled));

		assertThatThrownBy(() -> login("desk", "wrong-password")).isInstanceOf(BusinessException.class);

		assertThat(passwordEncoder.matchCount()).isEqualTo(1);
	}

	@Test
	void 없는_아이디도_비밀번호_해시를_한_번_계산한다() {
		when(users.findByLoginId("nobody")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> login("nobody", "wrong-password")).isInstanceOf(BusinessException.class);

		assertThat(passwordEncoder.matchCount()).isEqualTo(1);
	}

	// DB는 악센트나 전각 문자를 같은 글자로 본다. 그런 입력은 DB에 묻지도 않는다.
	@Test
	void 아이디로_쓸_수_없는_문자가_섞이면_DB를_조회하지_않는다() {
		assertThatThrownBy(() -> login("dęsk", PASSWORD)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> login("ｄesk", PASSWORD)).isInstanceOf(BusinessException.class);

		verify(users, never()).findByLoginId(any());
	}

	private LoginService newLoginService() {
		var provider = new DaoAuthenticationProvider(new AccountUserDetailsService(users));
		provider.setPasswordEncoder(passwordEncoder);
		return new LoginService(new ProviderManager(provider), mock(SessionAuthenticationStrategy.class),
				mock(SecurityContextRepository.class), users,
				new AuthProperties(List.of(), false, null, false, null, null, null), Clock.systemUTC());
	}

	private User savedUser() {
		User user = User.create("desk", passwordEncoder.encode(PASSWORD), "김직원", UserRole.STAFF, 1L);
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	private void login(String loginId, String password) {
		loginService.login(loginId, password, new MockHttpServletRequest(), new MockHttpServletResponse());
	}

	// 실제 인코더에 맡기되, 해시 비교를 몇 번 했는지 센다.
	private static final class CountingPasswordEncoder implements PasswordEncoder {

		private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		private final AtomicInteger matches = new AtomicInteger();

		@Override
		public String encode(CharSequence rawPassword) {
			return delegate.encode(rawPassword);
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			matches.incrementAndGet();
			return delegate.matches(rawPassword, encodedPassword);
		}

		int matchCount() {
			return matches.get();
		}

	}

}

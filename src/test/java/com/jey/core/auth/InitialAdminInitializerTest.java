package com.jey.core.auth;

import java.util.List;
import java.util.Map;

import com.jey.core.auth.AuthProperties.InitialAdmin;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class InitialAdminInitializerTest {

	private static final String PASSWORD = "first-admin-password";

	private final UserRepository users = mock(UserRepository.class);

	private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	@Test
	void 계정이_하나도_없으면_설정값으로_관리자를_만든다(CapturedOutput output) {
		when(users.count()).thenReturn(0L);

		initializer(new InitialAdmin("Owner", PASSWORD, "박원장", false)).createIfNoAccounts();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		User admin = saved.getValue();
		// 아이디는 소문자로 맞춰 저장한다.
		assertThat(admin.getLoginId()).isEqualTo("owner");
		assertThat(admin.getName()).isEqualTo("박원장");
		assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(admin.getCampusId()).isNull();
		assertThat(admin.isActive()).isTrue();
		// 비밀번호는 해시로만 저장하고 로그에도 남기지 않는다.
		assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
		assertThat(output).contains("첫 관리자 계정을 만들었다").doesNotContain(PASSWORD);
	}

	@Test
	void 이름을_주지_않으면_기본_이름을_쓴다() {
		when(users.count()).thenReturn(0L);

		initializer(new InitialAdmin("owner", PASSWORD, " ", false)).createIfNoAccounts();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("관리자");
	}

	// 이미 운영 중인 서버를 다시 띄울 때 관리자가 또 생기거나 비밀번호가 바뀌면 안 된다.
	@Test
	void 계정이_이미_있으면_만들지_않는다() {
		when(users.count()).thenReturn(3L);

		initializer(new InitialAdmin("owner", PASSWORD, "박원장", true)).createIfNoAccounts();

		verify(users, never()).save(any());
	}

	@Test
	void 설정값이_없으면_만들지_않고_경고를_남긴다(CapturedOutput output) {
		when(users.count()).thenReturn(0L);

		initializer(null).createIfNoAccounts();

		verify(users, never()).save(any());
		assertThat(output).contains("jey.auth.initial-admin");
	}

	// 운영에서 아무도 로그인할 수 없는 서버가 정상인 것처럼 뜨면 안 된다.
	@Test
	void 필수인데_설정값이_없으면_서버_시작을_멈춘다() {
		when(users.count()).thenReturn(0L);

		assertThatIllegalStateException()
				.isThrownBy(() -> initializer(new InitialAdmin(null, null, null, true)).createIfNoAccounts())
				.withMessageContaining("jey.auth.initial-admin");
		verify(users, never()).save(any());
	}

	// 환경변수 하나를 빠뜨린 경우다. "설정이 없다"와 구분해서 알린다.
	@Test
	void 아이디와_비밀번호_중_하나만_있으면_서버_시작을_멈춘다() {
		when(users.count()).thenReturn(0L);

		assertThatIllegalStateException()
				.isThrownBy(() -> initializer(new InitialAdmin("owner", " ", "박원장", false)).createIfNoAccounts())
				.withMessageContaining("하나만").withMessageContaining("password가 빠졌다");
		assertThatIllegalStateException()
				.isThrownBy(() -> initializer(new InitialAdmin(null, PASSWORD, "박원장", false)).createIfNoAccounts())
				.withMessageContaining("login-id가 빠졌다")
				// 어느 쪽이 빠졌는지는 알리되 비밀번호 값은 싣지 않는다.
				.withMessageNotContaining(PASSWORD);
		verify(users, never()).save(any());
	}

	@Test
	void 비밀번호가_너무_짧거나_길면_서버_시작을_멈춘다() {
		when(users.count()).thenReturn(0L);

		assertThatIllegalStateException()
				.isThrownBy(() -> initializer(new InitialAdmin("owner", "short", "박원장", false)).createIfNoAccounts())
				.withMessageContaining("10자 이상");
		// BCrypt는 72바이트까지만 받는다. 한글 25자는 75바이트다.
		assertThatIllegalStateException()
				.isThrownBy(() -> initializer(new InitialAdmin("owner", "가".repeat(25), "박원장", false))
						.createIfNoAccounts())
				.withMessageContaining("72바이트");
		verify(users, never()).save(any());
	}

	private InitialAdminInitializer initializer(InitialAdmin initialAdmin) {
		var properties = new AuthProperties(List.of(), false, null, false, Map.of(), Map.of(), initialAdmin);
		return new InitialAdminInitializer(users, passwordEncoder, properties);
	}

}

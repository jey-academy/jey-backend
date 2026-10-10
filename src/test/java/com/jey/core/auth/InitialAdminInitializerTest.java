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

		initializer(new InitialAdmin("owner", PASSWORD, "박원장")).createIfNoAccounts();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		User admin = saved.getValue();
		assertThat(admin.getLoginId()).isEqualTo("owner");
		assertThat(admin.getName()).isEqualTo("박원장");
		assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(admin.getCampusId()).isNull();
		assertThat(admin.isActive()).isTrue();
		// 비밀번호는 해시로만 저장하고 로그에도 남기지 않는다.
		assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
		assertThat(output).doesNotContain(PASSWORD);
	}

	@Test
	void 이름을_주지_않으면_기본_이름을_쓴다() {
		when(users.count()).thenReturn(0L);

		initializer(new InitialAdmin("owner", PASSWORD, " ")).createIfNoAccounts();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("관리자");
	}

	// 이미 운영 중인 서버를 다시 띄울 때 관리자가 또 생기거나 비밀번호가 바뀌면 안 된다.
	@Test
	void 계정이_이미_있으면_만들지_않는다() {
		when(users.count()).thenReturn(3L);

		initializer(new InitialAdmin("owner", PASSWORD, "박원장")).createIfNoAccounts();

		verify(users, never()).save(any());
	}

	@Test
	void 설정값이_없으면_만들지_않고_경고를_남긴다(CapturedOutput output) {
		when(users.count()).thenReturn(0L);

		initializer(null).createIfNoAccounts();
		initializer(new InitialAdmin("owner", " ", "박원장")).createIfNoAccounts();

		verify(users, never()).save(any());
		assertThat(output).contains("jey.auth.initial-admin");
	}

	private InitialAdminInitializer initializer(InitialAdmin initialAdmin) {
		var properties = new AuthProperties(List.of(), null, false, Map.of(), initialAdmin);
		return new InitialAdminInitializer(users, passwordEncoder, properties);
	}

}

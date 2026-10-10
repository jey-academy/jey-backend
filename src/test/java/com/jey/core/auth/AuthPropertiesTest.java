package com.jey.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.AuthProperties.InitialAdmin;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 설정이 잘못되면 서버가 뜨지 않아야 한다. 잘못된 설정으로 조용히 뜨면 운영에서야 알게 된다.
class AuthPropertiesTest {

	@Test
	void 값이_없으면_안전한_기본값을_쓴다() {
		var properties = new AuthProperties(null, false, " ", true, null, null, null);

		assertThat(properties.allowedOrigins()).isEmpty();
		assertThat(properties.hasCookieDomain()).isFalse();
		assertThat(properties.sessionTimeoutFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(2));
		assertThat(properties.sessionMaxLifetimeFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(12));
		assertThat(properties.initialAdmin().isConfigured()).isFalse();
		assertThat(properties.initialAdmin().required()).isFalse();
	}

	// 환경변수가 "있지만 비어 있는" 경우다. 그대로 뜨면 프론트의 모든 요청이 조용히 거부된다.
	@Test
	void 허용_출처가_필수인데_비어_있으면_거부한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of(), true, null, true, null, null, null))
				.withMessageContaining("allowed-origins");
		assertThat(new AuthProperties(List.of("https://app.example.com"), true, null, true, null, null, null)
				.allowedOrigins()).hasSize(1);
	}

	// 다른 설정값(계정 정보가 든 주소 등)을 잘못 넣었을 때 그 값이 오류 문구를 타고 로그에 남으면 안 된다.
	@Test
	void 출처_오류_문구에_계정_정보나_경로를_싣지_않는다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of("https://user:secret-pw@db.example.com/jey"), false, null,
						true, null, null, null))
				.withMessageContaining("allowed-origins")
				.withMessageNotContaining("secret-pw");
	}

	// 둘을 바꿔 적은 실수일 가능성이 크다. 최대 유지 시간이 더 짧으면 만료 시간 설정이 의미가 없어진다.
	@Test
	void 최대_유지_시간이_만료_시간보다_짧으면_거부한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of(), false, null, true,
						Map.of(UserRole.STAFF, Duration.ofHours(16)), Map.of(UserRole.STAFF, Duration.ofHours(12)),
						null))
				.withMessageContaining("session-max-lifetime.staff");
	}

	@Test
	void 역할별_시간을_읽는다() {
		var properties = new AuthProperties(List.of(), false, null, true,
				Map.of(UserRole.STAFF, Duration.ofHours(12)), Map.of(UserRole.STAFF, Duration.ofHours(16)), null);

		assertThat(properties.sessionTimeoutFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(12));
		assertThat(properties.sessionTimeoutFor(UserRole.ADMIN)).isEqualTo(Duration.ofHours(2));
		assertThat(properties.sessionMaxLifetimeFor(UserRole.STAFF)).isEqualTo(Duration.ofHours(16));
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://app.example.com", "http://localhost:5173", "https://app.example.com/" })
	void 올바른_출처는_받는다(String origin) {
		assertThat(new AuthProperties(List.of(origin), false, null, true, null, null, null).allowedOrigins())
				.containsExactly(origin);
	}

	// 환경변수가 없으면 "${AUTH_ALLOWED_ORIGINS}"라는 글자가 그대로 들어온다.
	@ParameterizedTest
	@ValueSource(strings = { "${AUTH_ALLOWED_ORIGINS}", "*", "https://*.example.com", " ", "app.example.com",
			"ftp://app.example.com", "https://app.example.com/path", "https://user@app.example.com",
			"https://app.example.com?x=1", "https://app.example.com#top", "https://app.example.com." })
	void 쓸_수_없는_출처는_거부한다(String origin) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of(origin), false, null, true, null, null, null))
				.withMessageContaining("allowed-origins");
	}

	// 단위를 빼먹은 "12"는 12밀리초로 읽힌다. 그대로 뜨면 로그인하자마자 세션이 끊긴다.
	@Test
	void 너무_짧은_세션_시간은_거부한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of(), false, null, true,
						Map.of(UserRole.STAFF, Duration.ofMillis(12)), null, null))
				.withMessageContaining("session-timeout.staff");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new AuthProperties(List.of(), false, null, true, null,
						Map.of(UserRole.ADMIN, Duration.ZERO), null))
				.withMessageContaining("session-max-lifetime.admin");
	}

	@Test
	void 첫_관리자_설정의_상태를_구분한다() {
		assertThat(new InitialAdmin("owner", "long-enough-password", null, false).isConfigured()).isTrue();
		assertThat(new InitialAdmin(null, null, null, false).isConfigured()).isFalse();
		assertThat(new InitialAdmin(null, null, null, false).isHalfConfigured()).isFalse();
		assertThat(new InitialAdmin("owner", " ", null, false).isHalfConfigured()).isTrue();
		assertThat(new InitialAdmin(" ", "long-enough-password", null, false).isHalfConfigured()).isTrue();
	}

	@Test
	void 첫_관리자_설정을_출력해도_비밀번호는_나오지_않는다() {
		assertThat(new InitialAdmin("owner", "top-secret-password", "박원장", true).toString())
				.contains("owner").doesNotContain("top-secret-password");
	}

}

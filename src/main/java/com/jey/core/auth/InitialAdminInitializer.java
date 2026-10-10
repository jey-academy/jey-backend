package com.jey.core.auth;

import com.jey.core.auth.AuthProperties.InitialAdmin;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 계정이 하나도 없을 때 설정값({@code jey.auth.initial-admin})으로 첫 관리자를 만든다.
 * 계정은 로그인한 관리자만 만들 수 있어야 하므로, 맨 처음 한 명은 로그인 없이 만들 방법이 필요하다.
 * 운영 비밀번호를 Git 저장소(설정 파일, 마이그레이션)에 두지 않으려고 실행 환경의 설정값으로 받는다.
 *
 * <p>계정이 하나도 없는데 설정이 잘못됐으면 예외를 던져 서버 시작을 멈춘다. 아무도 로그인할 수 없는 서버가 정상인 것처럼 뜨는 것을 막는다.
 * 계정이 이미 있으면 설정값을 읽지 않으므로 검사도 하지 않는다.
 */
@Component
class InitialAdminInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InitialAdminInitializer.class);

	private static final String DEFAULT_NAME = "관리자";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuthProperties properties;

	InitialAdminInitializer(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		createIfNoAccounts();
	}

	// 계정이 이미 있으면 아무것도 하지 않는다. 서버를 다시 띄워도 관리자가 또 생기거나 비밀번호가 바뀌지 않는다.
	void createIfNoAccounts() {
		if (users.count() > 0) {
			log.debug("계정이 이미 있어 첫 관리자를 만들지 않는다");
			return;
		}
		InitialAdmin initialAdmin = properties.initialAdmin();
		if (initialAdmin.isHalfConfigured()) {
			throw new IllegalStateException("첫 관리자 설정(jey.auth.initial-admin)에 아이디와 비밀번호 중 하나만 있다. "
					+ (initialAdmin.hasLoginId() ? "password" : "login-id") + "가 빠졌다.");
		}
		if (!initialAdmin.isConfigured()) {
			if (initialAdmin.required()) {
				throw new IllegalStateException(
						"계정이 하나도 없는데 첫 관리자 설정(jey.auth.initial-admin)이 없다. 아무도 로그인할 수 없다.");
			}
			log.warn("계정이 하나도 없는데 첫 관리자 설정(jey.auth.initial-admin)이 없다. 지금은 아무도 로그인할 수 없다.");
			return;
		}
		if (!initialAdmin.hasAcceptablePassword()) {
			throw new IllegalStateException(
					"첫 관리자 비밀번호(jey.auth.initial-admin.password)는 10자 이상, 72바이트 이하여야 한다(한글은 한 글자가 3바이트).");
		}
		String name = (initialAdmin.name() == null || initialAdmin.name().isBlank()) ? DEFAULT_NAME
				: initialAdmin.name();
		users.save(User.create(initialAdmin.loginId(), passwordEncoder.encode(initialAdmin.password()), name,
				UserRole.ADMIN, null));
		log.info("첫 관리자 계정을 만들었다: loginId={}", User.normalizeLoginId(initialAdmin.loginId()));
	}

}
